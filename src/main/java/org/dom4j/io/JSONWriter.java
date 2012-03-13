/*
 * Copyright 2007-2008 Krugle, Inc.
 * 
   Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *   http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */

package org.dom4j.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.dom4j.Attribute;
import org.dom4j.Comment;
import org.dom4j.Document;
import org.dom4j.DocumentType;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.Node;
import org.dom4j.Text;
import org.dom4j.tree.DefaultElement;
import org.dom4j.tree.NamespaceStack;

/* This first pass implementation doesn't handle namespaces, is basically
 * just a quick hack of XMLWriter, has very few JUnit tests (like XMLWriter),
 * and is almost completely devoid of meaningful comments. It also depends
 * on Java 5.0, whereas the 1.6.1 source was designed for Java 1.3.
 * On the plus side, it supports at least my own understanding of the three
 * JSON formats: Basic Output, RabbitFish and BabbleFish, which are thinly
 * documented on the following web pages:
 * 
 * http://json.org/
 * http://badgerfish.ning.com/
 * http://www.bramstein.nl/xsltjson/
 * 
 * Note: It does borrow the indentation support from XMLWriter,
 * which makes it easier to read the output while you're debugging
 * (just set humanFormat below to true).
 */
public class JSONWriter {
  protected static final JSONFormat DEFAULT_FORMAT = JSONFormat.RABBIT_FISH;

  /* We prefix any element names in the following list with "_" to avoid
   * JavaScript reserved words and client-side objects, methods,
   * or properties in Netscape Navigator or Internet Explorer.
   * 
   * (from http://www.javascripter.net/faq/reserved.htm)
   */
  private static final List<String> JAVASCRIPT_RESERVED_WORDS
    = Arrays.asList(new String[] {
      "abstract", "alert", "all", "anchor", "anchors", "area", "array",
      "assign", "blur", "boolean", "break", "button", "byte", "case", "catch",
      "char", "checkbox", "class", "clearTimeout", "clientInformation",
      "close", "closed", "confirm", "const", "continue", "crypto", "date",
      "debugger", "default", "defaultStatus", "delete", "do", "document",
      "double", "element", "elements", "else", "embed", "embeds", "enum",
      "escape", "eval", "event", "export", "extends", "false", "fileUpload",
      "final", "finally", "float", "focus", "for", "form", "forms", "frame",
      "frameRate", "frames", "function", "function", "getClass", "goto",
      "hidden", "history", "if", "image", "images", "implements", "import",
      "in", "innerHeight", "innerWidth", "instanceof", "int", "interface",
      "isNaN", "java", "JavaArray", "JavaClass", "JavaObject", "JavaPackage",
      "layer", "layers", "length", "link", "location", "long", "Math",
      "mimeTypes", "name", "native", "navigate", "navigator", "netscape",
      "new", "null", "Number", "Object", "offscreenBuffering", "onblur",
      "onerror", "onfocus", "onload", "onunload", "open", "opener", "option",
      "outerHeight", "outerWidth", "package", "packages", "pageXOffset",
      "pageYOffset", "parent", "parseFloat", "parseInt", "password", "pkcs11",
      "plugin", "private", "prompt", "protected", "prototype", "public",
      "radio", "reset", "return", "screenX", "screenY", "scroll", "secure",
      "select", "self", "setTimeout", "short", "static", "status", "String",
      "submit", "sun", "super", "switch", "synchronized", "taint", "text",
      "textarea", "this", "throw", "throws", "top", "toString", "transient",
      "true", "try", "typeof", "unescape", "untaint", "valueOf", "var", "void",
      "volatile", "while", "window", "with" 
    });

  /** The Writer used to output to */
  protected Writer writer;

  /** The Stack of namespaceStack written so far */
  private NamespaceStack namespaceStack = new NamespaceStack();

  /** The format used by this writer */
  private JSONFormat format;

  /** Whether a flush should occur after writing a document */
  private boolean autoFlush;

  /**
   * The initial number of indentations (so you can print a whole document
   * indented, if you like)
   */
  private int indentLevel = 0;
  
  /** buffer used when escaping strings */
  private StringBuffer buffer = new StringBuffer();

  /** Turn this on to get indentation, newlines, etc. */
  private boolean humanFormat = false;

  public JSONWriter(Writer writer) {
    this(writer, DEFAULT_FORMAT);
  }

  public JSONWriter(Writer writer, JSONFormat format) {
    this.writer = writer;
    this.format = format;
  }

  public JSONWriter() {
    this.format = DEFAULT_FORMAT;
    this.writer = new BufferedWriter(new OutputStreamWriter(System.out));
    this.autoFlush = true;
  }

  public JSONWriter(OutputStream out) throws UnsupportedEncodingException {
    this.format = DEFAULT_FORMAT;
    this.writer = createWriter(out, format.getEncoding());
    this.autoFlush = true;
  }

  public JSONWriter(OutputStream out, JSONFormat format)
  throws UnsupportedEncodingException {
    this.format = format;
    this.writer = createWriter(out, format.getEncoding());
    this.autoFlush = true;
  }

  public JSONWriter(JSONFormat format) throws UnsupportedEncodingException {
    this.format = format;
    this.writer = createWriter(System.out, format.getEncoding());
    this.autoFlush = true;
  }

  public void setWriter(Writer writer) {
    this.writer = writer;
    this.autoFlush = false;
  }

  public void setOutputStream(OutputStream out)
  throws UnsupportedEncodingException {
    this.writer = createWriter(out, format.getEncoding());
    this.autoFlush = true;
  }

  /**
   * Get an OutputStreamWriter, use preferred encoding.
   * 
   * @param outStream
   *            DOCUMENT ME!
   * @param encoding
   *            DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   * 
   * @throws UnsupportedEncodingException
   *             DOCUMENT ME!
   */
  protected Writer createWriter(OutputStream outStream, String encoding)
  throws UnsupportedEncodingException {
    return new BufferedWriter(new OutputStreamWriter(outStream, encoding));
  }

  /**
   * Set the initial indentation level. This can be used to output a document
   * (or, more likely, an element) starting at a given indent level, so it's
   * not always flush against the left margin. Default: 0
   * 
   * @param indentLevel
   *            the number of indents to start with
   */
  public void setIndentLevel(int indentLevel) {
    this.indentLevel = indentLevel;
  }

  /**
   * Flushes the underlying Writer
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void flush() throws IOException {
    writer.flush();
  }

  /**
   * Closes the underlying Writer
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void close() throws IOException {
    writer.close();
  }

  /**
   * Writes the new line text to the underlying Writer
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void println() throws IOException {
    writer.write(format.getLineSeparator());
  }

  /**
   * <p>
   * This will print the <code>Document</code> to the current Writer.
   * </p>
   * 
   * <p>
   * Warning: using your own Writer may cause the writer's preferred character
   * encoding to be ignored. If you use encodings other than UTF8, we
   * recommend using the method that takes an OutputStream instead.
   * </p>
   * 
   * <p>
   * Note: as with all Writers, you may need to flush() yours after this
   * method returns.
   * </p>
   * 
   * @param doc
   *            <code>Document</code> to format.
   * 
   * @throws IOException
   *             if there's any problem writing.
   */
  public void write(Document doc) throws IOException {
    if (doc.getDocType() != null) {
      indent();
      writeDocType(doc.getDocType());
      writer.write(" = ");
    }
    writer.write("{ ");
    
    ++indentLevel;
    writePrintln();
    indent();
    for (int i = 0, size = doc.nodeCount(); i < size; i++) {
      if (i > 0) {
        writer.write(", ");
        writePrintln();
        indent();
      }
      Node node = doc.node(i);
      writeNode(node);
    }
    
    writer.write(" ");
    --indentLevel;
    writePrintln();
    indent();
    writer.write("}");
    writePrintln();

    if (autoFlush) {
      flush();
    }
  }

  /**
   * <p>
   * Writes the <code>{@link Element}</code>, including its <code>{@link
   * Attribute}</code>
   * s, and its value, and all its content (child nodes) to the current
   * Writer.
   * </p>
   * 
   * @param element
   *            <code>Element</code> to output.
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void write(Element element) throws IOException {
    writeElement(element);

    if (autoFlush) {
      flush();
    }
  }

  /**
   * Writes the given {@link Namespace}.
   * 
   * @param namespace
   *            <code>Namespace</code> to output.
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void write(Namespace namespace) throws IOException {
    writeNamespace(namespace);

    if (autoFlush) {
      flush();
    }
  }

  /**
   * <p>
   * Print out a {@link String}, Perfoms the necessary entity escaping and
   * whitespace stripping.
   * </p>
   * 
   * @param text
   *            is the text to output
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void write(String text) throws IOException {
    writeString(text);

    if (autoFlush) {
      flush();
    }
  }

  /**
   * Writes the given {@link Node}.
   * 
   * @param node
   *            <code>Node</code> to output.
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void write(Node node) throws IOException {
    writeNode(node);

    if (autoFlush) {
      flush();
    }
  }

  /**
   * Writes the given object which should be a String, a Node or a List of
   * Nodes.
   * 
   * @param object
   *            is the object to output.
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  public void write(Object object) throws IOException {
    if (object instanceof Node) {
      write((Node) object);
    } else if (object instanceof String) {
      write((String) object);
    } else if (object instanceof List) {
      List<?> list = (List<?>) object;

      for (int i = 0, size = list.size(); i < size; i++) {
        write(list.get(i));
      }
    } else if (object != null) {
      throw new IOException("Invalid object: " + object);
    }
  }

// Implementation methods
  // -------------------------------------------------------------------------
  protected void writeElement(Element element) throws IOException {
    writer.write("\"");
    writer.write(getJsonElementName(element));
    writer.write("\"");
    writer.write(": ");
    writeElementContent(element);
  }
  
  /**
   * @param element in document
   * @return qualified name of element massaged into a valid JavaScript
   * identifier name to make JSON output valid. We replace hyphens ("-")
   * with underscores ("_"). All other invalid characters (e.g., "+")
   * are replaced with Unicode code point sequence of the form "_uXXXX_"
   * (e.g., "_u002B_"). JavaScript reserved words (e.g., "protected")
   * are prefixed with underscores (e.g., "_protected").
   */
  private String getJsonElementName(Element element) {
    char[] block = null;
    int i;
    int last = 0;
    String name = element.getQualifiedName().replaceAll("-", "_");
    
    if (isJavaScriptReservedWord(name)) {
      name = "_" + name;
    }
    
    int size = name.length();
    for (i = 0; i < size; i++) {
      char c = name.charAt(i);
      
      if  (   (   (i == 0)
              &&  (!Character.isJavaIdentifierStart(c)))
          ||  (!Character.isJavaIdentifierPart(c))) {
        int codePoint = Character.codePointAt(name, i);
        if (block == null) {
          block = name.toCharArray();
        }

        buffer.append(block, last, i - last);
        buffer.append(String.format("_u%04X_", codePoint));
        last = i + 1;
      }

    }

    if (last == 0) {
      return name;
    }

    if (last < size) {
      buffer.append(block, last, i - last);
    }

    String result = buffer.toString();
    buffer.setLength(0);

    return result;
  }
  
  private boolean isJavaScriptReservedWord(String name) {
    return JAVASCRIPT_RESERVED_WORDS.contains(name);
  }

  protected void writeElementMixedContent(Element element) throws IOException {
    int attributeCount = element.attributeCount();
    writer.write("[ ");
    
    ++indentLevel;
    writePrintln();
    indent();
    for (int i = 0; i < attributeCount; i++) {
      if (i > 0) {
        writer.write(", ");
        writePrintln();
        indent();
      }
      writeAttribute(element.attribute(i));
    }
    for (int i = 0, nodesWritten = 0, nodeCount = element.nodeCount(); i < nodeCount; i++) {
      Node node = element.node(i);
      
      // Skip any whitespace-only Text nodes
      if  (   (node instanceof Text)
          &&  (node.getText().trim().length() == 0)) {
        continue;
      }
      
      if ((attributeCount + nodesWritten) > 0) {
        writer.write(", ");
        writePrintln();
        indent();
      }
      if  (   (node instanceof Element)
          ||  (format.equals(JSONFormat.BADGER_FISH))) {
        writer.write("{ ");
        
        ++indentLevel;
        writePrintln();
        indent();
        if (!(node instanceof Element)) {
          writer.write("\"$\": ");
        }
        writeNode(node);

        writer.write(" ");
        
        --indentLevel;
        writePrintln();
        indent();
        writer.write("}");
        
      } else {
        writeNode(node);
      }
      
      nodesWritten++;
    }
    writer.write(" ");
    
   --indentLevel;
    writePrintln();
    indent();
    writer.write("]");
  }
  
  protected void writeElementContent(Element element) throws IOException {
    
    // Mixed content (element and text nodes) at the same level become
    // array elements.
    if (element.hasMixedContent()) {
      writeElementMixedContent(element);
      return;
    }
    
    // BASIC_OUTPUT & RABBIT_FISH: Text content goes directly in the value
    // of an object. For BADGER_FISH, we end up here processing the "$:"
    // property we add below.
    if  (   (element.attributeCount() == 0)
        &&  element.isTextOnly()
        &&  (   format.equals(JSONFormat.BASIC_OUTPUT)
            ||  format.equals(JSONFormat.RABBIT_FISH)
            ||  element.getName().equals("$"))) {
      writeNodeText(element);
      return;
    }
    
    // We have to collect all children with the same name into an array
    // which becomes the value of that property
    ArrayList<ArrayList<Node>> properties = new ArrayList<ArrayList<Node>>();
    
    // Loop over the attributes to help determine answers to the above
    for (int i = 0, attributeCount = element.attributeCount(); i < attributeCount; i++) {
      addProperty(properties, element.attribute(i));
    }
    
    // Concatenate any Text nodes into a single property
    if (element.isTextOnly()) {
      addProperty(properties, new DefaultElement("$").addText(element.getText()));
    }

    // Loop over the nodes to help determine answers to the above
    for (int i = 0, nodeCount = element.nodeCount(); i < nodeCount; i++) {
      Node node = element.node(i);

      if (node instanceof Namespace) {
        // namespaces not supported yet
        
      } else if (node instanceof Comment) {
        // comments ignored
        
      } else if (node instanceof Element) {
        addProperty(properties, node);
        
      } else if (node instanceof Text) {
        // text nodes were concatenated above
        
      } else {
        // ignore everything else
      }
    }

    writer.write("{ ");
      
    ++indentLevel;
    writePrintln();
    indent();
    for (int i = 0, propertyCount = properties.size(); i < propertyCount; i++) {
      if (i > 0) {
        writer.write(", ");
        writePrintln();
        indent();
      }
      writeProperty(properties.get(i));
    }
    writer.write(" ");
    
    --indentLevel;
    writePrintln();
    indent();
    writer.write("}");
  }

  protected void addProperty( ArrayList<ArrayList<Node>> properties,
                              Node property) {
    ArrayList<Node> targetPropertyList = null;
    if  (   (property instanceof Element)
        &&  (!property.getName().equals("$"))) {
      for (ArrayList<Node> propertyList : properties) {
        if  (   (propertyList.get(0) instanceof Element)
            &&  (propertyList.get(0).getName().equals(property.getName()))) {
          targetPropertyList = propertyList;
        }
      }
    }
    if (targetPropertyList == null) {
      targetPropertyList = new ArrayList<Node>();
      properties.add(targetPropertyList);
    }
    targetPropertyList.add(property);
  }

  protected void writeProperty(ArrayList<Node> property) throws IOException {
    if (property.size() == 1) {
      writeNode(property.get(0));
      
    } else {
      String propertyName = ((Element)(property.get(0))).getName();
      writer.write("\"");
      writer.write(propertyName);
      writer.write("\": ");
      writer.write("[ ");
      
      ++indentLevel;
      writePrintln();
      indent();
      Iterator<Node> nodeIterator = property.iterator();
      for (int nodeIndex = 0; nodeIterator.hasNext(); nodeIndex++) {
        if (nodeIndex > 0) {
          writer.write(", ");
          writePrintln();
          indent();
        }
        writeElementContent((Element)(nodeIterator.next()));
      }
      
      writer.write(" ");
      --indentLevel;
      writePrintln();
      indent();
      writer.write("]");
    }
  }

  protected void writeDocType(DocumentType docType) throws IOException {
    if (docType != null) {
      writePrintln();
      indent();
      docType.write(writer);
    }
  }

  protected void writeNamespace(Namespace namespace) throws IOException {
    if (namespace != null) {
      writeNamespace(namespace.getPrefix(), namespace.getURI());
    }
  }

  protected void writeAttribute(Attribute attribute) throws IOException {
    writer.write("\"");
    if  (   format.equals(JSONFormat.BADGER_FISH)
        ||  format.equals(JSONFormat.RABBIT_FISH)) {
      writer.write("@");
    }
    writer.write(attribute.getQualifiedName());
    writer.write("\": ");
    writeString(attribute.getText());
  }

  /**
   * Writes the SAX namepsaces
   * 
   * @param prefix
   *            the prefix
   * @param uri
   *            the namespace uri
   * 
   * @throws IOException
   */
  protected void writeNamespace(String prefix, String uri) 
  throws IOException {
    throw new IOException("Namespaces not yet supported!");
  }

  protected void writeTextNode(Text textNode) throws IOException {
    writeNodeText(textNode);
  }

  protected void writeNodeText(Node node) throws IOException {
    writeString(node.getText());
  }

  protected void writeString(String text) throws IOException {
    if (text != null) {
      text = escapeElementEntities(text.trim());

      writer.write("\"");
      writer.write(text);
      writer.write("\"");
    }
  }
  
  protected void writeNode(Node node) throws IOException {
    int nodeType = node.getNodeType();

    switch (nodeType) {
    case Node.NAMESPACE_NODE:
      writeNamespace((Namespace) node);

      break;

    case Node.ATTRIBUTE_NODE:
      writeAttribute((Attribute) node);

      break;

    case Node.ELEMENT_NODE:
      writeElement((Element) node);

      break;

    case Node.TEXT_NODE:
      writeTextNode((Text) node);
      
      break;

    case Node.ENTITY_REFERENCE_NODE:
      // Skip entities, whatever they are

      break;

    case Node.COMMENT_NODE:
      // Skip comments

      break;

    case Node.DOCUMENT_NODE:
      write((Document) node);

      break;

    case Node.DOCUMENT_TYPE_NODE:
      writeDocType((DocumentType) node);

      break;

    default:
      throw new IOException("Invalid node type: " + node);
    }
  }

  protected void indent() throws IOException {
    if (humanFormat) {
      for (int i = 0; i < indentLevel; i++) {
        writer.write("  ");
      }
    }
  }

  /**
   * <p>
   * This will print a new line only if the newlines flag was set to true
   * </p>
   * 
   * @throws IOException
   *             DOCUMENT ME!
   */
  protected void writePrintln() throws IOException {
    if (humanFormat) {
      writer.write(format.getLineSeparator());
    }
  }

  /**
   * This will take the pre-defined entities in XML 1.0 and convert their
   * character representation to the appropriate entity reference, suitable
   * for XML attributes.
   * 
   * @param text
   *            DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   */
  protected String escapeElementEntities(String text) {
    char[] block = null;
    int i;
    int last = 0;
    int size = text.length();

    for (i = 0; i < size; i++) {
      String entity = null;
      char c = text.charAt(i);

      switch (c) {
      case '"':
        entity = "\\\"";

        break;

      case '/':
        entity = "\\/";

        break;

      case '\\':
        entity = "\\\\";

        break;
      }

      if (entity != null) {
        if (block == null) {
          block = text.toCharArray();
        }

        buffer.append(block, last, i - last);
        buffer.append(entity);
        last = i + 1;
      }
    }

    if (last == 0) {
      return text;
    }

    if (last < size) {
      if (block == null) {
        block = text.toCharArray();
      }

      buffer.append(block, last, i - last);
    }

    String answer = buffer.toString();
    buffer.setLength(0);

    return answer;
  }

  protected void writeEscapeAttributeEntities(String txt) throws IOException {
      if (txt != null) {
          String escapedText = escapeAttributeEntities(txt);
          writer.write(escapedText);
      }
  }

  /**
   * This will take the pre-defined entities in XML 1.0 and convert their
   * character representation to the appropriate entity reference, suitable
   * for XML attributes.
   * 
   * @param text
   *            DOCUMENT ME!
   * 
   * @return DOCUMENT ME!
   */
  protected String escapeAttributeEntities(String text) {
    char[] block = null;
    int i;
    int last = 0;
    int size = text.length();

    for (i = 0; i < size; i++) {
      String entity = null;
      char c = text.charAt(i);

      switch (c) {
      case '"':
        entity = "\\\"";

        break;

      case '/':
        entity = "\\/";

        break;

      case '\\':
        entity = "\\\\";

        break;
      }

      if (entity != null) {
        if (block == null) {
          block = text.toCharArray();
        }

        buffer.append(block, last, i - last);
        buffer.append(entity);
        last = i + 1;
      }
    }

    if (last == 0) {
      return text;
    }

    if (last < size) {
      if (block == null) {
        block = text.toCharArray();
      }

      buffer.append(block, last, i - last);
    }

    String answer = buffer.toString();
    buffer.setLength(0);

    return answer;
  }

  protected boolean isNamespaceDeclaration(Namespace ns) {
    if  (   format.getUseNamespaces()
        &&  (ns != null)
        &&  (ns != Namespace.XML_NAMESPACE)) {
      String uri = ns.getURI();

      if (uri != null) {
        if (!namespaceStack.contains(ns)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Lets subclasses get at the current format object, so they can call
   * setTrimText, setNewLines, etc. Put in to support the HTMLWriter, in the
   * way that it pushes the current newline/trim state onto a stack and
   * overrides the state within preformatted tags.
   * 
   * @return DOCUMENT ME!
   */
  protected JSONFormat getOutputFormat() {
    return format;
  }

}

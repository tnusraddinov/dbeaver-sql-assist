package com.sqlassist.dbeaver.values;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/** Pretty-prints XML with the JDK parser. DTDs and external entities are refused. */
public final class XmlFormatter {

    private XmlFormatter() {
    }

    public static String pretty(String xml) throws FormatException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null); // report via exception only, never to stderr
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            stripWhitespace(doc);

            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, xml.strip().startsWith("<?xml") ? "no" : "yes");
            StringWriter writer = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString().strip();
        } catch (SAXParseException e) {
            throw new FormatException(e.getMessage(), offsetOf(xml, e.getLineNumber(), e.getColumnNumber()));
        } catch (Exception e) {
            throw new FormatException(e.getMessage() == null ? e.toString() : e.getMessage(), -1);
        }
    }

    /** Whitespace-only text between elements would otherwise be kept and indented again. */
    private static void stripWhitespace(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else {
                stripWhitespace(child);
            }
            child = next;
        }
    }

    private static int offsetOf(String text, int line, int column) {
        if (line < 1) {
            return -1;
        }
        int offset = 0;
        for (int l = 1; l < line && offset >= 0; l++) {
            offset = text.indexOf('\n', offset) + 1;
            if (offset == 0) {
                return -1;
            }
        }
        return Math.min(text.length(), offset + Math.max(0, column - 1));
    }
}

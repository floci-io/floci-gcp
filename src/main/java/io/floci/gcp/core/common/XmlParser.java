package io.floci.gcp.core.common;

import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight StAX-based XML parsing helpers. Used by GCS for XML request bodies
 * (multipart uploads, bucket configurations, etc.).
 */
public final class XmlParser {

    private static final Logger LOG = Logger.getLogger(XmlParser.class);

    private static final XMLInputFactory FACTORY;

    static {
        FACTORY = XMLInputFactory.newInstance();
        FACTORY.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    private XmlParser() {}

    /** Strict repeated records for APIs where accepting a partial XML document is unsafe. */
    public static List<Map<String, String>> parseRecords(String xml, String root, String record) {
        List<Map<String, String>> result = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            synchronized (FACTORY) { reader = FACTORY.createXMLStreamReader(new StringReader(xml == null ? "" : xml)); }
            int depth = 0;
            Map<String, String> current = null;
            boolean seenRoot = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) { throw new XMLStreamException("Entities are not permitted"); }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = reader.getLocalName();
                    if (depth == 1) {
                        if (seenRoot || !name.equals(root)) { throw new XMLStreamException("Unexpected root"); }
                        seenRoot = true;
                    } else if (depth == 2) {
                        if (!name.equals(record)) { throw new XMLStreamException("Unexpected record"); }
                        current = new LinkedHashMap<>();
                    } else if (depth == 3 && current != null) {
                        String value = reader.getElementText().strip();
                        if (current.put(name, value) != null) { throw new XMLStreamException("Duplicate field"); }
                        depth--;
                    } else { throw new XMLStreamException("Unexpected nested element"); }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 2) { result.add(current); current = null; }
                    depth--;
                }
            }
            if (!seenRoot || depth != 0) { throw new XMLStreamException("Incomplete document"); }
            return result;
        } catch (XMLStreamException e) {
            throw GcpException.invalidArgument("Malformed XML request").withReason("MalformedXML");
        } finally {
            if (reader != null) { try { reader.close(); } catch (XMLStreamException ignored) {} }
        }
    }

    private static String readLeafText(XMLStreamReader r) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(r.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                skipElement(r);
                return null;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        return sb.toString();
    }

    private static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    /** Reads a flat map of element local-name → text content from an XML string.
     *  Only direct children of the root element are captured; nested elements are ignored. */
    public static Map<String, String> parseFlat(String xml) {
        Map<String, String> result = new LinkedHashMap<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            int depth = 0;
            String currentName = null;
            StringBuilder currentText = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 2) {
                        currentName = r.getLocalName();
                        currentText = new StringBuilder();
                    } else if (depth > 2) {
                        currentName = null;
                        currentText = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 2 && currentName != null && currentText != null) {
                        String text = currentText.toString().strip();
                        if (!text.isBlank()) {
                            result.put(currentName, text);
                        }
                    }
                    depth--;
                    if (depth < 2) {
                        currentName = null;
                        currentText = null;
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && currentText != null) {
                    currentText.append(r.getText());
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            LOG.debugv("Ignoring malformed XML during parse: {0}", e.getMessage());
        }
        return result;
    }

    /** Reads repeated elements with the given local name and returns their text values. */
    public static List<String> parseList(String xml, String elementName) {
        List<String> result = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        try {
            XMLStreamReader r = FACTORY.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && elementName.equals(r.getLocalName())) {
                    String text = readLeafText(r);
                    if (text != null) {
                        result.add(text.strip());
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            LOG.debugv("Ignoring malformed XML during parse: {0}", e.getMessage());
        }
        return result;
    }
}

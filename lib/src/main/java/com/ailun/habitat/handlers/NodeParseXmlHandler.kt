package com.ailun.habitat.handlers

import android.util.Log
import com.ailun.habitat.INodeHandler
import com.ailun.habitat.WorkflowContext
import com.ailun.habitat.WorkflowNode
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [ACTION_PARSE_XML]：解析 XML 字符串并提取元素内容。
 *
 * params：
 * - `xml`（必填）：XML 字符串
 * - `xpath`（可选）：标签名或标签路径，如 "root.item.name"（匹配嵌套结构）
 * - `output_var`（可选）：存储解析结果的变量名
 * - `attribute`（可选）：如果指定，提取该属性值而非文本内容
 */
class NodeParseXmlHandler : INodeHandler {

    override suspend fun handle(node: WorkflowNode, context: WorkflowContext): String? {
        val params = node.params ?: return node.next

        val rawXml = params["xml"]?.toString()?.trim() ?: run {
            Log.w(TAG, "No XML string provided")
            context.putVariable("xml_success", false)
            return node.next
        }

        val xmlString = context.interpolate(rawXml)

        // Try to resolve as variable
        val actualXml = if (!xmlString.trimStart().startsWith("<")) {
            context.getVariable(rawXml)?.toString() ?: xmlString
        } else {
            xmlString
        }

        val xpath = params["xpath"]?.toString()?.trim()?.ifEmpty { null }
        val outputVar = params["output_var"]?.toString()?.trim()?.ifEmpty { null }
        val attribute = params["attribute"]?.toString()?.trim()?.ifEmpty { null }

        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isIgnoringComments = true
                isIgnoringElementContentWhitespace = true
                // XXE 防护：禁止 DOCTYPE / 外部实体（不支持的 feature 忽略）
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(org.xml.sax.InputSource(StringReader(actualXml)))

            val result: Any? = if (xpath != null) {
                extractByPath(document, xpath, attribute)
            } else {
                // Return the full text content of the root element
                document.documentElement?.textContent ?: ""
            }

            val resultStr = when (result) {
                is List<*> -> {
                    // Build a JSON array of results
                    val sb = StringBuilder("[")
                    result.forEachIndexed { index, item ->
                        if (index > 0) sb.append(",")
                        sb.append("\"${item.toString().replace("\"", "\\\"")}\"")
                    }
                    sb.append("]")
                    sb.toString()
                }
                null -> ""
                else -> result.toString()
            }

            val resultKey = outputVar ?: "xml_parsed_value"
            context.putVariable("xml_parsed_value", resultStr)
            context.putVariable(resultKey, resultStr)
            context.putVariable("xml_success", true)

            Log.i(TAG, "XML parsed successfully${if (xpath != null) " at path: $xpath" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "XML parse failed: ${e.message}", e)

            // Fallback: regex-based parsing for simple cases
            if (xpath != null && !actualXml.startsWith("<")) {
                Log.w(TAG, "XML does not start with <, treating as plain text for regex fallback")
            }
            try {
                val fallbackResult = regexExtract(actualXml, xpath ?: "", attribute)
                if (fallbackResult != null) {
                    val resultKey = outputVar ?: "xml_parsed_value"
                    context.putVariable("xml_parsed_value", fallbackResult)
                    context.putVariable(resultKey, fallbackResult)
                    context.putVariable("xml_success", true)
                    Log.i(TAG, "XML parsed via regex fallback: $fallbackResult")
                    return node.next
                }
            } catch (_: Exception) {
                // Regex fallback also failed
            }

            context.putVariable("xml_success", false)
            context.putVariable("xml_error", e.message ?: "Unknown error")
        }

        return node.next
    }

    /**
     * Navigate a DOM using a dot-separated tag path.
     */
    private fun extractByPath(document: Document, path: String, attribute: String?): Any? {
        val tags = path.split(".").filter { it.isNotEmpty() }
        if (tags.isEmpty()) return null

        val lastTag = tags.last()
        var currentElements: NodeList = document.getElementsByTagName(lastTag)

        if (currentElements.length == 0) {
            // No match for the last tag, try matching the whole path as a single tag
            currentElements = document.getElementsByTagName(path)
        }

        if (currentElements.length == 0) return null

        val results = mutableListOf<String>()

        for (i in 0 until currentElements.length) {
            val element = currentElements.item(i)

            // If there are parent tags, verify the hierarchy
            if (tags.size > 1) {
                var parent = element.parentNode
                var matchesPath = true
                for (j in tags.size - 2 downTo 0) {
                    if (parent == null || parent.nodeName != tags[j]) {
                        matchesPath = false
                        break
                    }
                    parent = parent.parentNode
                }
                if (!matchesPath) continue
            }

            val value = if (attribute != null) {
                element.attributes?.getNamedItem(attribute)?.nodeValue ?: ""
            } else {
                element.textContent?.trim() ?: ""
            }

            if (value.isNotEmpty()) {
                results.add(value)
            }
        }

        return when {
            results.isEmpty() -> null
            results.size == 1 -> results[0]
            else -> results
        }
    }

    /**
     * Simple regex-based XML extraction as fallback.
     */
    private fun regexExtract(xml: String, tag: String, attribute: String?): String? {
        val lastTag = Regex.escape(tag.split(".").lastOrNull() ?: tag)

        val textPattern = Regex("<$lastTag[^>]*>(.*?)</$lastTag>", RegexOption.DOT_MATCHES_ALL)
        val textMatch = textPattern.find(xml)
        if (textMatch != null && attribute == null) {
            return textMatch.groupValues[1].trim()
        }

        if (attribute != null) {
            val attr = Regex.escape(attribute)
            val attrPattern = Regex("<$lastTag[^>]*$attr=\"([^\"]*)\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
            val attrMatch = attrPattern.find(xml)
            if (attrMatch != null) {
                return attrMatch.groupValues[1]
            }
        }

        return null
    }

    companion object {
        private const val TAG = "HabitatParseXml"
    }
}

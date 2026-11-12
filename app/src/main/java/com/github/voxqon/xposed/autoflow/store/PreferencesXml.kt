package com.github.voxqon.xposed.autoflow.store

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

internal object PreferencesXml {
    fun stringValue(xml: String, key: String): String? =
        readValue(xml, key, "string") { it.nextText() }

    fun booleanValue(xml: String, key: String): Boolean? =
        readValue(xml, key, "boolean") {
            it.getAttributeValue(null, "value")?.toBooleanStrictOrNull()
        }

    private fun <T> readValue(xml: String, key: String, tag: String, read: (XmlPullParser) -> T?): T? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2 &&
                parser.name == tag && parser.getAttributeValue(null, "name") == key
            ) {
                return read(parser)
            }
            parser.next()
        }
        return null
    }
}

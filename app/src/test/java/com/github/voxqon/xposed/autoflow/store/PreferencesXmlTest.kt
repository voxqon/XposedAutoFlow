package com.github.voxqon.xposed.autoflow.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesXmlTest {
    @Test
    fun stringValuesDecodeNamedAndNumericEntitiesExactlyOnce() {
        val xml = """<map><string name="rules_json">[{&quot;name&quot;:&quot;A &amp; B &lt; C &#10; &#x4E2D; &amp;quot;&quot;}]</string></map>"""
        assertEquals("[{\"name\":\"A & B < C \n \u4e2d &quot;\"}]", PreferencesXml.stringValue(xml, "rules_json"))
    }

    @Test
    fun stringValuesSupportCdataAndAttributeOrder() {
        val xml = """<map><string other='value' name='rules_json'><![CDATA[[{"name":"<Home>"}]]]></string></map>"""
        assertEquals("""[{"name":"<Home>"}]""", PreferencesXml.stringValue(xml, "rules_json"))
    }

    @Test
    fun shellFlagCannotUseTheFollowingPreferencesValue() {
        val xml = """<map><boolean name="allow_shell" value="false"/><boolean name="other" value="true"/></map>"""
        assertEquals(false, PreferencesXml.booleanValue(xml, "allow_shell"))
    }

    @Test
    fun booleanValuesRequireTheExactKeyTypeAndValue() {
        assertEquals(true, PreferencesXml.booleanValue("""<map><boolean value='true' name='allow_shell'/></map>""", "allow_shell"))
        assertNull(PreferencesXml.booleanValue("""<map><string name="allow_shell">true</string></map>""", "allow_shell"))
        assertNull(PreferencesXml.booleanValue("""<map><boolean name="other" value="true"/></map>""", "allow_shell"))
        assertNull(PreferencesXml.booleanValue("""<map><boolean name="allow_shell" value="invalid"/></map>""", "allow_shell"))
    }

    @Test
    fun missingOrEmptyRuleValuesAreDistinguishable() {
        assertEquals("", PreferencesXml.stringValue("""<map><string name="rules_json"/></map>""", "rules_json"))
        assertNull(PreferencesXml.stringValue("""<map><string name="other">[]</string></map>""", "rules_json"))
    }
}

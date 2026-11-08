package com.iris.localai.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestParserTest {

    @Test
    fun parsesOperitJsonManifest() {
        val manifest = PluginManifestParser.parse(
            """
            {
              "schema_version": 1,
              "toolpkg_id": "com.example.demo",
              "version": "1.2.0",
              "api_version": "1.0.0",
              "main": "dist/main.js",
              "display_name": { "zh": "演示插件", "en": "Demo" },
              "description": { "zh": "演示描述" },
              "author": "tester",
              "enabled_by_default": false,
              "subpackages": [
                { "id": "demo", "entry": "dist/demo.js" }
              ],
              "requires": [
                { "id": "base.pkg", "description": "基础包", "min_version": "1.0.0" }
              ],
              "resources": [
                { "key": "logo", "path": "resources/logo.png", "mime": "image/png" }
              ],
              "wasm_modules": [
                { "id": "core", "path": "modules/core.wasm", "exports": ["add"], "abi": "assemblyscript" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("com.example.demo", manifest.id)
        assertEquals("演示插件", manifest.name)
        assertEquals("演示描述", manifest.description)
        assertEquals(listOf("tester"), manifest.author)
        assertEquals(PluginOrigin.OPERIT, manifest.origin)
        assertEquals("dist/demo.js", manifest.subpackages.single().entry)
        assertEquals("base.pkg", manifest.requirements.single().id)
        assertEquals("resources/logo.png", manifest.resources.single().path)
        assertEquals("modules/core.wasm", manifest.wasmModules.single().path)
        assertEquals(listOf("add"), manifest.wasmModules.single().exports)
        assertTrue(!manifest.enabledByDefault)
    }

    @Test
    fun parsesHjsonManifestWithCommentsAndBareValues() {
        val manifest = PluginManifestParser.parse(
            """
            {
              // HJSON 注释
              schema_version: 1
              toolpkg_id: com.example.hjson
              version: 0.1.0
              display_name: {
                zh: HJSON 插件
                en: HJSON Plugin
              }
              subpackages: [
                { id: main, entry: index.js }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("com.example.hjson", manifest.id)
        assertEquals("HJSON 插件", manifest.name)
        assertEquals("index.js", manifest.subpackages.single().entry)
    }

    @Test
    fun parsesHjsonWithBomAndInlineComments() {
        val manifest = PluginManifestParser.parse(
            "\uFEFF{\n" +
                "  toolpkg_id: com.example.bom\n" +
                "  version: 1.0.0 # 行内注释\n" +
                "  display_name: BOM 插件\n" +
                "}",
        )

        assertEquals("com.example.bom", manifest.id)
        assertEquals("1.0.0", manifest.version)
        assertEquals("BOM 插件", manifest.name)
    }

    @Test
    fun parsesOperitMetadataTools() {
        val tools = PluginManifestParser.parseMetadata(
            """
            "use strict";
            /* METADATA
            {
              "name": "demo",
              "tools": [
                {
                  "name": "hello",
                  "description": { "zh": "打招呼" },
                  "parameters": [
                    { "name": "who", "description": "对象", "type": "string", "required": true },
                    { "name": "times", "description": "次数", "type": "integer" }
                  ]
                },
                { "name": "ping", "description": "Ping" }
              ]
            }
            */
            exports.hello = function (params) { return params; };
            """.trimIndent(),
        )

        assertEquals(2, tools.size)
        assertEquals("hello", tools[0].name)
        assertEquals("打招呼", tools[0].description)
        assertEquals(2, tools[0].parameters.size)
        assertEquals("who", tools[0].parameters[0].name)
        assertTrue(tools[0].parameters[0].required)
        assertTrue(tools[0].parameters[1].required)
        assertEquals("ping", tools[1].name)
    }

    @Test
    fun sanitizesPluginIds() {
        assertEquals("com.example.demo", sanitizePluginId("com.example.demo"))
        assertEquals("com_example_demo", sanitizePluginId("com/example demo"))
        assertEquals("plugin", sanitizePluginId("///"))
    }
}

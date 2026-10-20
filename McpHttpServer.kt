package com.example.androidmcp

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal MCP server over Streamable HTTP (JSON responses only).
 * POST /mcp with "Authorization: Bearer <token>".
 */
class McpHttpServer(port: Int, private val token: String) : NanoHTTPD("0.0.0.0", port) {

    private val instructions =
        "Controls this Android phone. Call get_screen_state to see the UI, then use tap_node / tap / swipe / type_text. " +
            "Node ids change after every get_screen_state or find_nodes call. Prefer node actions over raw coordinates."

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health") return text(Response.Status.OK, "ok")

        // Token may come as "Authorization: Bearer <token>" or inside the URL: /mcp/<token>
        val uri = session.uri
        val auth = session.headers["authorization"] ?: ""
        val pathToken = if (uri.startsWith("/mcp/")) uri.removePrefix("/mcp/").trimEnd('/') else null
        val authorized = same(auth, "Bearer $token") || (pathToken != null && same(pathToken, token))
        if (!authorized) return text(Response.Status.UNAUTHORIZED, "unauthorized")
        if (uri != "/mcp" && pathToken == null) return text(Response.Status.NOT_FOUND, "not found")

        return when (session.method) {
            Method.POST -> handlePost(session)
            Method.DELETE -> text(Response.Status.OK, "")
            else -> text(Response.Status.METHOD_NOT_ALLOWED, "use POST")
        }
    }

    private fun same(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun handlePost(session: IHTTPSession): Response {
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (t: Throwable) {
            return json(Response.Status.BAD_REQUEST, error(null, -32700, "Could not read body: ${t.message}"))
        }
        val parsed: Any = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (t: Throwable) {
            return json(Response.Status.BAD_REQUEST, error(null, -32700, "Parse error"))
        }

        if (parsed is JSONArray) {
            val out = JSONArray()
            for (i in 0 until parsed.length()) {
                val r = rpc(parsed.getJSONObject(i))
                if (r != null) out.put(r)
            }
            return if (out.length() == 0) accepted() else json(Response.Status.OK, out)
        }
        val r = rpc(parsed as JSONObject) ?: return accepted()
        return json(Response.Status.OK, r)
    }

    /** Returns null for notifications (no response body). */
    private fun rpc(req: JSONObject): JSONObject? {
        val hasId = req.has("id")
        val id = req.opt("id")
        val method = req.optString("method")
        if (!hasId) return null

        return try {
            when (method) {
                "initialize" -> {
                    val version = req.optJSONObject("params")?.optString("protocolVersion").orEmpty()
                        .ifEmpty { "2025-03-26" }
                    ok(
                        id, JSONObject()
                            .put("protocolVersion", version)
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "android-mcp").put("version", "0.1.0"))
                            .put("instructions", instructions)
                    )
                }
                "ping" -> ok(id, JSONObject())
                "tools/list" -> {
                    val arr = JSONArray()
                    Tools.all.forEach { arr.put(it.toJson()) }
                    ok(id, JSONObject().put("tools", arr))
                }
                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val tool = Tools.find(name) ?: return error(id, -32602, "Unknown tool: $name")
                    val result = try {
                        tool.run(args)
                    } catch (t: Throwable) {
                        Tools.errorResult(t.message ?: t.javaClass.simpleName)
                    }
                    ok(id, result)
                }
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (t: Throwable) {
            error(id, -32603, t.message ?: "Internal error")
        }
    }

    private fun ok(id: Any?, result: JSONObject) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun error(id: Any?, code: Int, msg: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", msg))

    private fun json(status: Response.IStatus, body: Any): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun text(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    private fun accepted(): Response = newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", "")
}

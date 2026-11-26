package app.coredump.net

import app.coredump.platform.Platform
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders

/**
 * Browser rules for the web app (no effect on the phone apps):
 * - no User-Agent / Api-User-Agent headers: the browser sends its own, and a custom one turns a plain request into one
 *   that needs the server's extra permission (most of the APIs used here don't give it);
 * - Wikimedia's action API (…/w/api.php) answers a page from another site only when asked with origin=*.
 */
val WebSafe = createClientPlugin("WebSafe") {
    onRequest { request, _ ->
        if (!Platform.web) return@onRequest
        request.headers.remove(HttpHeaders.UserAgent)
        request.headers.remove("Api-User-Agent")
        val host = request.url.host
        val wiki = host.endsWith("wikipedia.org") || host.endsWith("wikidata.org") || host.endsWith("wikimedia.org")
        if (wiki && request.url.pathSegments.lastOrNull() == "api.php" && request.url.parameters["origin"] == null) {
            request.url.parameters.append("origin", "*")
        }
    }
}

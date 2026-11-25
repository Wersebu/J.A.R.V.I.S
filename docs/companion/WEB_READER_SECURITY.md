# Web Reader security

HttpWebPageReader now uses Apache HttpClient 5 with a connection-manager DNS resolver. It validates every returned address and gives those exact addresses to the connection operator, so the transport does not perform a second unchecked hostname lookup. URL prevalidation alone is not the security boundary. TLS hostname verification remains enabled; proxies, retries, automatic redirects and automatic content decompression are not enabled.

Redirect responses fail closed (3xx); no destination is followed, including public-to-private redirects. Broader crawling and safe redirect following are intentionally not enabled yet. Credential-bearing URLs are rejected. Loopback, unspecified, link-local, private/site-local, multicast, carrier-grade NAT and non-global IPv6 addresses are blocked; transition/documentation IPv6 prefixes are excluded. Mixed public/private DNS answers are rejected. A protected resolver seam exists only for tests, not a configurable production bypass.

Bodies are bounded at 2 MiB plus one detection byte; oversized responses fail instead of being partially trusted. Encoded bodies are refused. Connect/socket deadlines and a total-request cancellation deadline prevent unbounded slow reads. Results remain untrusted data; no fetched code is executed. Extraction is still the existing text/structured-data/link extraction, not a browser or PDF crawler.

Tests cover normal extraction, forbidden IP ranges, redirect refusal without hitting its target, oversized bodies, and a hostname that changes from a public address during prevalidation to loopback at connection resolution. No broader crawl capability is enabled by these changes. OS egress controls remain a useful additional boundary, but are not used to excuse unchecked DNS resolution.

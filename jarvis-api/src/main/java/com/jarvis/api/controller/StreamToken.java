package com.jarvis.api.controller;

/**
 * One-time correlation token returned by the chat stream POST handoff, to be passed to the
 * follow-up SSE GET call.
 *
 * @param token correlation token
 */
public record StreamToken(String token) {
}

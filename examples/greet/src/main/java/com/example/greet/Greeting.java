package com.example.greet;

import java.time.Instant;

/** Object produced by {@code greet}. */
public record Greeting(String name, String message, Instant at) {
}

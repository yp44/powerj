package com.example.greet;

import java.time.Instant;

/** Objet produit par {@code greet}. */
public record Greeting(String name, String message, Instant at) {
}

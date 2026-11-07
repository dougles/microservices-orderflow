package com.orderflow.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Raíz del repo y variables de {@code deploy/.env} (las del entorno tienen prioridad). */
public final class Env {

  private static final Path ROOT = findRoot();
  private static final Map<String, String> DOT_ENV = readDotEnv();

  private Env() {}

  public static Path root() {
    return ROOT;
  }

  public static Path composeFile() {
    return ROOT.resolve("deploy/docker-compose.yml");
  }

  public static String get(String name) {
    String value = System.getenv(name);
    if (value == null) {
      value = DOT_ENV.get(name);
    }
    if (value == null) {
      throw new IllegalStateException(name + " no está en el entorno ni en deploy/.env");
    }
    return value;
  }

  private static Path findRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("deploy/docker-compose.yml"))) {
        return p;
      }
    }
    throw new IllegalStateException("no se encontró deploy/docker-compose.yml desde " + dir);
  }

  private static Map<String, String> readDotEnv() {
    Path file = ROOT.resolve("deploy/.env");
    var values = new HashMap<String, String>();
    if (!Files.exists(file)) {
      return values;
    }
    try {
      for (String line : Files.readAllLines(file)) {
        String trimmed = line.strip();
        int eq = trimmed.indexOf('=');
        if (trimmed.isEmpty() || trimmed.startsWith("#") || eq < 1) {
          continue;
        }
        values.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return values;
  }
}

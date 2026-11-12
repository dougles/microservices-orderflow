package com.orderflow.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Inyección de fallos con {@code docker compose pause|unpause|stop|start} (SPEC §14). Con varias
 * réplicas (catalog-service) el comando afecta a todas.
 */
public final class Compose {

  private static final Pattern STATE = Pattern.compile("\"State\"\\s*:\\s*\"(\\w+)\"");
  private static final long TIMEOUT_SECONDS = 120;

  private Compose() {}

  public static void pause(String service) {
    run("pause", service);
  }

  public static void stop(String service) {
    run("stop", service);
  }

  /** No-op si ningún contenedor del servicio está pausado: seguro en {@code @AfterEach}. */
  public static void unpause(String service) {
    if (states(service).contains("paused")) {
      run("unpause", service);
    }
  }

  /** No-op si todos los contenedores del servicio ya corren: seguro en {@code @AfterEach}. */
  public static void start(String service) {
    Set<String> states = states(service);
    if (states.isEmpty() || !states.equals(Set.of("running"))) {
      run("start", service);
    }
  }

  /** Estados ({@code running}, {@code paused}, {@code exited}…) de los contenedores del servicio. */
  public static Set<String> states(String service) {
    return parseStates(run("ps", "--all", "--format", "json", service));
  }

  /** Acepta los dos formatos de {@code ps --format json}: array JSON o un objeto por línea. */
  static Set<String> parseStates(String out) {
    Matcher m = STATE.matcher(out);
    var states = new ArrayList<String>();
    while (m.find()) {
      states.add(m.group(1));
    }
    return states.stream().collect(Collectors.toUnmodifiableSet());
  }

  /** Ejecuta {@code docker compose -f deploy/docker-compose.yml <args>}; falla si exit ≠ 0. */
  public static String run(String... args) {
    var command = new ArrayList<>(List.of("docker", "compose", "-f", Env.composeFile().toString()));
    command.addAll(List.of(args));
    try {
      Process process =
          new ProcessBuilder(command).directory(Env.root().toFile()).redirectErrorStream(true).start();
      String output;
      try (var in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("timeout: " + String.join(" ", command));
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "exit " + process.exitValue() + ": " + String.join(" ", command) + "\n" + output);
      }
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}

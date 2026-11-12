package com.orderflow.messaging.kafka;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** La autorización (ROLE_ops sobre {@code /ops/**}) la aplica la seguridad de cada servicio. */
@RestController
public class DltReplayController {

  private final DltReplayer replayer;

  DltReplayController(DltReplayer replayer) {
    this.replayer = replayer;
  }

  @PostMapping("/ops/dlt/{topic}/replay")
  public ResponseEntity<?> replay(
      @PathVariable("topic") String topic,
      @RequestParam(name = "max", defaultValue = "100") int max) {
    if (!replayer.consumes(topic)) {
      var problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
      problem.setProperty("code", "NOT_FOUND");
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
    return ResponseEntity.ok(Map.of("replayed", replayer.replay(topic, max)));
  }
}

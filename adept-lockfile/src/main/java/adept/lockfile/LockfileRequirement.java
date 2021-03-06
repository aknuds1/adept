package adept.lockfile;

import adept.artifact.models.JsonSerializable;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.Set;

public class LockfileRequirement implements JsonSerializable { //TODO: equals, hashCode
  public final Id id;
  public final Set<Constraint> constraints;
  public final Set<Id> exclusions;

  public LockfileRequirement(Id id, Set<Constraint> constraints, Set<Id> exclusions) {
    this.id = id;
    this.constraints = constraints;
    this.exclusions = exclusions;
  }

  public void writeJson(JsonGenerator generator) throws IOException {
    generator.writeStringField("id", id.value);
    generator.writeArrayFieldStart("constraints");
    for (Constraint constraint: constraints) {
      generator.writeStartObject();
      constraint.writeJson(generator);
      generator.writeEndObject();
    }
    generator.writeEndArray();
    generator.writeArrayFieldStart("exclusions");
    for (Id exclusion: exclusions) {
      generator.writeString(exclusion.value);
    }
    generator.writeEndArray();
  }
}

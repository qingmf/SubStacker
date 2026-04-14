package com.substacker.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ErrorMapper implements ExceptionMapper<Exception> {
  @Override
  public Response toResponse(Exception exception) {
    int status = (exception instanceof IllegalArgumentException) ? 400 : 500;
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON_TYPE)
        .entity(new ErrorResponse(exception.getMessage()))
        .build();
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorResponse(String message) {
  }
}


package com.substacker.api;

import com.substacker.model.Upstream;
import com.substacker.service.UpstreamService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/upstreams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UpstreamResource {
  private final UpstreamService upstreamService;

  public UpstreamResource(UpstreamService upstreamService) {
    this.upstreamService = upstreamService;
  }

  @GET
  public List<Upstream> list() {
    return upstreamService.list();
  }

  @POST
  public Upstream create(Upstream upstream) {
    return upstreamService.create(upstream);
  }

  @PUT
  @Path("/{id}")
  public Upstream update(@PathParam("id") String id, Upstream upstream) {
    return upstreamService.update(id, upstream);
  }

  @DELETE
  @Path("/{id}")
  public void delete(@PathParam("id") String id) {
    upstreamService.delete(id);
  }
}


/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.rest.resources;

import com.akiban.ais.model.IndexName;
import com.akiban.rest.ResourceRequirements;
import com.akiban.rest.RestResponseBuilder;
import com.akiban.util.tap.InOutTap;
import com.akiban.util.tap.Tap;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.PrintWriter;

import static com.akiban.rest.resources.ResourceHelper.MEDIATYPE_JSON_JAVASCRIPT;

/**
 * Full text query against index.
 */
@Path("/text/{table}/{index}")
public class FullTextResource {
    private final ResourceRequirements reqs;
    private static final InOutTap TEXT_GET = Tap.createTimer("rest: text get");

    public FullTextResource(ResourceRequirements reqs) {
        this.reqs = reqs;
    }

    @GET
    @Produces(MEDIATYPE_JSON_JAVASCRIPT)
    public Response textSearch(@Context final HttpServletRequest request,
                               @PathParam("table") String table,
                               @PathParam("index") String index,
                               @QueryParam("q") final String query,
                               @QueryParam("depth") final Integer depth,
                               @QueryParam("size") final Integer limit) throws Exception {
        final IndexName indexName = new IndexName(ResourceHelper.parseTableName(request, table), index);
        ResourceHelper.checkSchemaAccessible(reqs.securityService, request, indexName.getSchemaName());
        TEXT_GET.in();
        try {
            return RestResponseBuilder
                    .forRequest(request)
                    .body(new RestResponseBuilder.BodyGenerator() {
                        @Override
                        public void write(PrintWriter writer) throws Exception {
                            reqs.restDMLService.fullTextSearch(writer, indexName, depth, query, limit);
                        }
                    })
                    .build();
        } finally {
            TEXT_GET.out();
        }
    }
}

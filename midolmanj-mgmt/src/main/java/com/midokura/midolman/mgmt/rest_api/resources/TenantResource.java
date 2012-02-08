/*
 * @(#)TenantResource        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.dto.UriResource;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Root resource class for tenants.
 *
 * @version 1.6 07 Sept 2011
 * @author Ryu Ishimoto
 */
public class TenantResource {
    /*
     * Implements REST API endpoints for tenants.
     */

    private final static Logger log = LoggerFactory
            .getLogger(TenantResource.class);

    /**
     * Handler for creating a tenant.
     *
     * @param tenant
     *            Tenant object.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @Consumes({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(Tenant tenant, @Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        if (!authorizer.isAdmin(context)) {
            throw new UnauthorizedException("Not authorized to create tenant.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        String id = null;
        try {
            id = dao.create(tenant);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
        return Response.created(ResourceUriBuilder.getTenant(uriInfo.getBaseUri(), id))
                .build();
    }

    /**
     * Handler for deleting a tenant.
     *
     * @param id
     *            Tenant ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     */
    @DELETE
    @Path("{id}")
    public void delete(@PathParam("id") String id,
            @Context SecurityContext context, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws StateAccessException,
            UnauthorizedException {

        if (!authorizer.isAdmin(context)) {
            throw new UnauthorizedException("Not authorized to delete tenant.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        try {
            dao.delete(id);
        } catch (NoStatePathException e) {
            // Deleting a non-existing record is OK.
            log.warn("The resource does not exist", e);
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }
    }

    /**
     * Bridge resource locator for tenants
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantBridgeResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.BRIDGES)
    public TenantBridgeResource getBridgeResource(@PathParam("id") String id) {
        return new TenantBridgeResource(id);
    }

    /**
     * Router resource locator for tenants.
     *
     * @param id
     *            Tenant ID from the request.
     * @returns TenantRouterResource object to handle sub-resource requests.
     */
    @Path("/{id}" + ResourceUriBuilder.ROUTERS)
    public TenantRouterResource getRouterResource(@PathParam("id") String id) {
        return new TenantRouterResource(id);
    }

    /**
     * Handler for listing all the tenants.
     *
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @returns A list of Tenant objects.
     */
    @GET
    @Produces({ VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<Tenant> list(@Context SecurityContext context,
            @Context UriInfo uriInfo, @Context DaoFactory daoFactory,
            @Context Authorizer authorizer) throws UnauthorizedException,
            StateAccessException {

        if (!authorizer.isAdmin(context)) {
            throw new UnauthorizedException("Not authorized to view tenants.");
        }

        TenantDao dao = daoFactory.getTenantDao();
        List<Tenant> tenants = null;
        try {
            tenants = dao.list();
        } catch (StateAccessException e) {
            log.error("StateAccessException error.");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error.");
            throw new UnknownRestApiException(e);
        }

        for (UriResource resource : tenants) {
            resource.setBaseUri(uriInfo.getBaseUri());
        }
        return tenants;
    }

    /**
     * Handler to getting a tenant.
     *
     * @param id
     *            Tenant ID from the request.
     * @param context
     *            Object that holds the security data.
     * @param uriInfo
     *            Object that holds the request URI data.
     * @param daoFactory
     *            Data access factory object.
     * @param authorizer
     *            Authorizer object.
     * @throws StateAccessException
     *             Data access error.
     * @throws UnauthorizedException
     *             Authentication/authorization error.
     * @return A Tenant object.
     */
    @GET
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_TENANT_JSON,
            MediaType.APPLICATION_JSON })
    public Tenant get(@PathParam("id") String id,
            @Context SecurityContext context, @Context UriInfo uriInfo,
            @Context DaoFactory daoFactory, @Context Authorizer authorizer)
            throws UnauthorizedException, StateAccessException {
        TenantDao dao = daoFactory.getTenantDao();

        if (!authorizer.tenantAuthorized(context, AuthAction.READ, id)) {
            throw new UnauthorizedException("Not authorized to view tenants.");
        }

        Tenant tenant = null;
        try {
            tenant = dao.get(id);
        } catch (StateAccessException e) {
            log.error("Error accessing data");
            throw e;
        } catch (Exception e) {
            log.error("Unhandled error");
            throw new UnknownRestApiException(e);
        }

        tenant.setBaseUri(uriInfo.getBaseUri());
        return tenant;
    }
}

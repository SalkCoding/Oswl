package com.salkcoding.oswl.controller.scim;

import com.salkcoding.oswl.controller.spec.ScimUserControllerSpec;
import com.salkcoding.oswl.dto.scim.ScimError;
import com.salkcoding.oswl.dto.scim.ScimPatchRequest;
import com.salkcoding.oswl.dto.scim.ScimUser;
import com.salkcoding.oswl.service.scim.ScimProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * SCIM 2.0 Users resource endpoint.
 *
 * Authentication is performed by {@link com.salkcoding.oswl.web.interceptor.ScimAuthInterceptor}
 * using a dedicated SCIM-scoped API key.
 */
@RestController
@RequestMapping("/scim/v2/Users")
@RequiredArgsConstructor
public class ScimUserController implements ScimUserControllerSpec {

    private final ScimProvisioningService scimProvisioningService;

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getUser(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(scimProvisioningService.getUser(id));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listUsers(
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(name = "count", defaultValue = "100") int count) {
        return ResponseEntity.ok(scimProvisioningService.listUsers(filter, startIndex, count));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createUser(@RequestBody ScimUser request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(scimProvisioningService.createUser(request));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody ScimUser request) {
        try {
            return ResponseEntity.ok(scimProvisioningService.updateUser(id, request));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> patchUser(@PathVariable Long id, @RequestBody ScimPatchRequest request) {
        try {
            return ResponseEntity.ok(scimProvisioningService.patchUser(id, request));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        try {
            scimProvisioningService.deactivateUser(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    private ResponseEntity<ScimError> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ScimError.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                        .status(String.valueOf(HttpStatus.NOT_FOUND.value()))
                        .detail(detail)
                        .build());
    }

    private ResponseEntity<ScimError> badRequest(String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ScimError.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                        .status(String.valueOf(HttpStatus.BAD_REQUEST.value()))
                        .detail(detail)
                        .build());
    }
}

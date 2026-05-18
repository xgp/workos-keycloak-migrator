package io.phasetwo.migration.common.workos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.model.Page;
import io.phasetwo.migration.common.workos.model.WConnection;
import io.phasetwo.migration.common.workos.model.WDirectory;
import io.phasetwo.migration.common.workos.model.WOrganization;
import io.phasetwo.migration.common.workos.model.WRole;
import io.phasetwo.migration.common.workos.model.WUser;
import org.junit.jupiter.api.Test;

class ModelDeserializationTest {

    @Test
    void userWithMetadataAndNulls() throws Exception {
        String json = """
                {
                  "object":"user","id":"user_1","email":"a@x.com","email_verified":true,
                  "first_name":"Mar","last_name":"Davis","profile_picture_url":null,
                  "external_id":null,"last_sign_in_at":null,"locale":"en-US",
                  "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z",
                  "metadata":{"tier":"diamond"}
                }
                """;
        WUser u = JsonUtil.mapper().readValue(json, WUser.class);
        assertThat(u.id()).isEqualTo("user_1");
        assertThat(u.metadata()).containsEntry("tier", "diamond");
        assertThat(u.locale()).isEqualTo("en-US");
    }

    @Test
    void organizationPageWithDomains() throws Exception {
        String json = """
                {"object":"list","data":[
                  {"object":"organization","id":"org_1","name":"Acme","metadata":{},"external_id":null,
                   "domains":[
                     {"object":"organization_domain","id":"d1","organization_id":"org_1",
                      "domain":"acme.com","state":"verified",
                      "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}
                   ],
                   "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}
                ],"list_metadata":{"before":null,"after":null}}
                """;
        Page<WOrganization> page = JsonUtil.mapper().readValue(json, new TypeReference<>() {});
        assertThat(page.data()).hasSize(1);
        WOrganization o = page.data().get(0);
        assertThat(o.name()).isEqualTo("Acme");
        assertThat(o.domains().get(0).isVerified()).isTrue();
    }

    @Test
    void connectionWithSigningCert() throws Exception {
        String json = """
                {"object":"connection","id":"conn_1","organization_id":"org_1",
                 "connection_type":"OktaSAML","name":"Okta","state":"active","status":"linked",
                 "domains":[],"options":{"signing_cert":"---CERT---"},
                 "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}
                """;
        WConnection c = JsonUtil.mapper().readValue(json, WConnection.class);
        assertThat(c.connectionType()).isEqualTo("OktaSAML");
        assertThat(c.options().signingCert()).isEqualTo("---CERT---");
        assertThat(c.isActive()).isTrue();
    }

    @Test
    void directoryAndRoleParse() throws Exception {
        String roleJson = """
                {"object":"role","id":"role_1","slug":"admin","name":"Admin",
                 "description":"Can do anything","type":"EnvironmentRole",
                 "resource_type_slug":"organization",
                 "permissions":["posts:read","posts:write"],
                 "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}
                """;
        WRole r = JsonUtil.mapper().readValue(roleJson, WRole.class);
        assertThat(r.isEnvironmentRole()).isTrue();
        assertThat(r.permissions()).contains("posts:read");

        String dirJson = """
                {"object":"directory","id":"directory_1","organization_id":"org_1",
                 "external_key":"k","type":"okta scim v2.0","state":"linked","name":"Okta",
                 "created_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}
                """;
        WDirectory d = JsonUtil.mapper().readValue(dirJson, WDirectory.class);
        assertThat(d.isLinked()).isTrue();
    }
}

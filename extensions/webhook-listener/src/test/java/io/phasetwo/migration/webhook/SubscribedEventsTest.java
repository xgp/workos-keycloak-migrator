package io.phasetwo.migration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubscribedEventsTest {

    @Test
    void coversAllEntityEvents() {
        assertThat(WorkOSWebhookProviderFactory.SUBSCRIBED_EVENTS)
                .contains(
                        "user.created", "user.updated", "user.deleted",
                        "organization.created", "organization.deleted",
                        "organization_membership.created",
                        "organization_domain.verified",
                        "role.created", "organization_role.created",
                        "connection.activated", "connection.deactivated",
                        "dsync.activated", "dsync.user.created", "dsync.group.created");
    }

    @Test
    void doesNotSubscribeToAuthenticationOrSessionEvents() {
        assertThat(WorkOSWebhookProviderFactory.SUBSCRIBED_EVENTS)
                .noneMatch(e -> e.startsWith("authentication."))
                .noneMatch(e -> e.startsWith("session."));
    }
}

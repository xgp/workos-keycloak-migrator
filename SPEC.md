# WorkOS to Keycloak migrator

You are building tools to assist the migration of an identity system from WorkOS to Keycloak. The target Keycloak has Phase Two’s extensions installed for organizations and webhooks.

## Goals

The goal is to provide tools and extensions to complete a migration of entities from WorkOS to Keycloak that is as complete as possible. Because some things do not map exactly 1-1, it is important to let the administrator know when there are issues with synchronization, or partial synchronization of entities occurred. It is assumed that administrators will make partial, incremental migrations, so these tools should be built with that in mind.

### Entities to synchronize

⁃ Users. Import users, their attributes and metadata about their use. It may be necessary to create custom Keycloak user attributes as you discover which attributes exist in WorkOS that are not default in Keycloak. Import each users "Identities" from WorkOS using Keycloak's "linked identity providers" functionality which preserves the link to the identity provider and the brokered username.
⁃ Roles. Import roles of type "Environment" and "Custom" from WorkOS. "Environment" roles are equivalent to "Realm" roles in Keycloak. "Custom" roles are equivalent to Phase Two's "Organization" roles in Keycloak. Custom roles are defined at the organization level and are specific to individual organizations. It may be necessary to create new Phase Two Organization roles in each Organization to account for roles that do not exist by default.
⁃ Organizations. Import organizations from WorkOS, which has a very similar model to Phase Two Organizations in Keycloak. Any metadata in WorkOS that is not represented by default in the Phase Two Organization object can be added as organization attributes. Organization verfied domains in WorkOS should also be imported as Phase Two Organization Domains, with the verfication flag provided.
⁃ Identify providers. Import WorkOS "SSO connections" into Keycloak identity providers, correctly associated with the imported Phase Two Organizations in Keycloak. If possible, find the metadata associated with the SSO connection so that the maximum amount of information can be imported into Keycloak. There may not be enough data to fully configure the identity provider, but you should create an incomplete one in order to provide a stub for the administrator to create later.
⁃ Directory connections. Import Directory Sync connections from WorkOS into Phase Two Organization SCIM providers in Keycloak. There may be not enough data to fully configure the SCIM provider, but you should create an incomplete one in order to provide a stub for the administrator to create later.

## Resources
⁃ Documentation
  - WorkOS
    - Docs: https://workos.com/docs
    - API: https://workos.com/docs/reference
  - Keycloak
    - Docs: https://github.com/keycloak/keycloak/tree/main/docs/documentation
	- API: https://www.keycloak.org/docs-api/latest/rest-api/index.html
  - Phase Two:
    - Docs: https://phasetwo.io/docs/introduction/
	- API: https://phasetwo.io/api/phase-two-admin-rest-api/
⁃ OpenAPI spec files
  - WorkOS: openapi/workos-openapi.yaml
  - Keycloak: openapi/keycloak-openapi.yaml
  - Phase Two: openapi/phasetwo-openapi.yaml
⁃ Java API libraries
  - WorkOS
    - Library: https://github.com/workos/workos-kotlin
    - Javadoc: https://javadoc.io/doc/com.workos/workos/latest/index.html
  - Keycloak
    - Library: https://github.com/keycloak/keycloak/tree/main/integration/admin-client
    - Javadoc: https://javadoc.io/doc/org.keycloak/keycloak-admin-client/latest/index.html
  - Phase Two
    - Library: https://github.com/p2-inc/phasetwo-java
    - Javadoc: https://javadoc.io/doc/io.phasetwo/phasetwo-admin-client/latest/index.html

## Tools

While a complete, instantaneous, bulk migration would be ideal, you need to provide the administrator the ability to do incremental migration that is tracked, restartable, and durable. It is likely that the two systems (WorkOS and Keycloak) will be run in parallel for some time, requiring updates in WorkOS to be propagated to Keycloak in situations where a migration has already begun. Therefore it is important when creating entities, that checks be made for duplication, and, where possible, information about the migration state and time be stored in Keycloak (for example in User or Organization attributes, for entity-specific state or in Realm attributes for tracking global state).

There are 3 core components you need to build. This should be constructed as a multi module maven application with the parent pom in the root directory. There may be code that will be shared among the components that should be placed in a `common/` directory and expressed as a dependency in the other components. For example, the logic to synchronize a WorkOS entity to a Keycloak or Phase Two entity will be reused in several places, and should live in `common`.

### Bulk migration runner

The bulk migration runner is a standalone Java application that uses the WorkOS API to discover the state of configuration, and then synchronizes the state of the "Entities to synchronize" described above into Keycloak. It should be possible to run this application multiple times in an idempotent way. It may also store state for status of a migration in the target Keycloak (for example as Realm attributes) and give the administrator the ability to resume a migration in progress if it should fail.

The runner will be built in the `migrator/` directory, and specified as a maven submodule. This will build a fat jar that can be run with `java -jar ...` and given the necessary command line parameters required. It will be required to give the migrator a full-access service account ("API Key" in WorkOS) on both the WorkOS and Keycloak realm sides, and to provide the URLs. 

#### Notes

- Make sure the WorkOS API rate limits are respected: https://workos.com/docs/api/markdown/reference/rate-limits
- Provide sensible logging so that the administrator can track the progress of the migration, see counts of successful entity imports, see errors and skipped entities in a way that will help debug, etc. 

### Webhook listener extension

In order to listen for changes in WorkOS and make incremental sync possible, you will implement a WorkOS webhook listener as a Keycloak RealmResourceProvider at the `workos-webhook/{id}` path. 

#### Resources

- WorkOS Webhook API: https://workos.com/docs/api/markdown/reference/webhooks
- WorkOS example for syncing with webhooks: https://workos.com/docs/api/markdown/events/data-syncing/webhooks

When a webhook is received, check the state of the local entity, if present, and use the WorkOS API to get the remote state in order to synchronize the change. Synchronize the change as in the bulk migrator, using the same entity state tracking so that it can be used by the above bulk migrator as information when performing an addition bulk run.

In the postInit method of the REST resource provider factory, it should set up the webhook in the source WorkOS system using a service account configured in provider variables. In order to make sure duplicate webhook endpoints are not set up, the endpoint URL should be something that is unique (e.g. add an `{id}` to the realm resource and store the ID you use for that as a Realm attribute). The shared secret should also be stored as a Realm attribute. The configuration of the WorkOS webhook should only listen to the entities that are being synchronized.

### Slow migration extension

The target keycloak will have the [`keycloak-user-migration`](https://github.com/daniel-frak/keycloak-user-migration) extension installed. This will be used in order to migrate passwords from WorkOS once the administrator has switched to using the Keycloak login user interface. This extension allows remote endpoints to be called when a user is loaded and password verified in Keycloak. You need to implement those endpoints as a Keycloak RealmResourceProvider that the `keycloak-user-migration` can call. 

#### Resources
- `keycloak-user-migration` extension documentation:  https://raw.githubusercontent.com/daniel-frak/keycloak-user-migration/refs/heads/master/README.md

The requests should accept a username in the path, as described in the `keycloak-user-migration` documentation. The requests should be authenticated using bearer auth, and you can store the token to use as a realm attribute.

- The POST accepts the password in the JSON body, and is used to verify a password for the user. This should use the WorkOS authorize endpoint with the `password` grant type in order to validate the user's password.
- The GET provides a User object that represents all of the information we have about the user. This should use the WorkOS userinfo endpoint, with augmentation from the WorkOS user and identity APIs. Note that the schema for returning to the `keycloak-user-migration` includes "organizations". We will skip this in the returned object, as it pertains to Keycloak native organizations, not Phase Two organizations. 

In the postInit method of the REST resource provider factory, it should set up a `keycloak-user-migration` component model configuration. It should use a naming convention so that it does not set up duplicates.

## Test and validation

Create reasonable unit test coverage for each of the applications and extensions. This can use Keycloak Testcontainers.

In order for you to do validation of the bulk migrator, create a local keycloak with the extensions installed. You should do this using a docker-compose file you create, and run the validation against a realm you create.

For the purpose of testing, I have created a WorkOS account. You can create entities as you see fit. API keys for the "Sandbox" and "Demo" environments are shared out-of-band; set them via the `WORKOS_API_KEY` environment variable when running the migrator.

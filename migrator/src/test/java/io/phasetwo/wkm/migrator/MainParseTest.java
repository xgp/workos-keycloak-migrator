package io.phasetwo.wkm.migrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MainParseTest {

    @Test
    void parsesAllAsEverything() throws Exception {
        Method m = Main.class.getDeclaredMethod("parseEntities", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> all = (java.util.Set<String>) m.invoke(null, "all");
        assertThat(all).contains("users", "organizations", "roles", "idps", "directories", "memberships");
    }

    @Test
    void parsesCommaList() throws Exception {
        Method m = Main.class.getDeclaredMethod("parseEntities", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> some = (java.util.Set<String>) m.invoke(null, "users,roles");
        assertThat(some).containsExactlyInAnyOrder("users", "roles");
    }

    @Test
    void rejectsUnknownEntity() throws Exception {
        Method m = Main.class.getDeclaredMethod("parseEntities", String.class);
        m.setAccessible(true);
        assertThatThrownBy(() -> {
            try { m.invoke(null, "users,nope"); }
            catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        }).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.openlibrary.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleCanReadTheCatalogue(Role role) {
        assertThat(role.authorities()).contains(Role.CATALOG_READ);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void authoritiesAreStableAndDoNotRecurse(Role role) {
        assertThat(role.authorities())
                .as("a role must derive the same set every time")
                .isEqualTo(role.authorities())
                .isNotEmpty()
                .doesNotContainNull();
    }

    @Test
    void aReaderCannotWriteAnything() {
        assertThat(Role.LECTOR.authorities())
                .doesNotContain(Role.CATALOG_WRITE, Role.USERS_READ, Role.LOANS_OPERATE);
    }

    @Test
    void aLibrarianRunsLoansAndStockButNotTheUsers() {
        assertThat(Role.BIBLIOTECARIO.authorities())
                .contains(Role.LOANS_OPERATE, Role.INVENTORY_WRITE, Role.CATALOG_READ)
                .doesNotContain(Role.USERS_READ, Role.CATALOG_WRITE);
    }

    @Test
    void administrativeStaffManagePeopleButNotRoles() {
        assertThat(Role.ADMINISTRATIVO.authorities())
                .contains(Role.USERS_READ, Role.USERS_WRITE, Role.CATALOG_WRITE)
                .doesNotContain(Role.USERS_ROLES, Role.SETTINGS_MANAGE);
    }

    @Test
    void onlyTheAdministratorHoldsEveryAuthority() {
        assertThat(Role.ADMINISTRADOR.authorities())
                .contains(Role.USERS_ROLES, Role.SETTINGS_MANAGE, Role.BACKUP_MANAGE)
                .containsAll(Role.ADMINISTRATIVO.authorities());
    }

    @Test
    void everyAuthorityStringIsUniqueAcrossRoles() {
        var all = java.util.Arrays.stream(Role.values())
                .flatMap(r -> r.authorities().stream())
                .distinct()
                .toList();
        var declared = java.util.Set.of(
                Role.CATALOG_READ, Role.CATALOG_WRITE, Role.INVENTORY_WRITE,
                Role.LOANS_OPERATE, Role.LOANS_SELF, Role.USERS_READ, Role.USERS_WRITE,
                Role.USERS_ROLES, Role.SETTINGS_MANAGE, Role.BACKUP_MANAGE);

        assertThat(all).containsExactlyInAnyOrderElementsOf(declared);
    }
}
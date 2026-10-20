package io.floci.gcp.services.iam.authorization;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamConditionEvaluator;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.IamPrincipalResolver;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamResourceHierarchy;
import io.floci.gcp.services.iam.IamRoleCatalog;
import io.floci.gcp.services.iam.IamService;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamAuthorizationServiceTest {
    @ParameterizedTest
    @EnumSource(EmulatorConfig.IamAuthorizationMode.class)
    void internalServiceAuthorizationRejectsAnonymousAndNonServiceAccountPrincipals(EmulatorConfig.IamAuthorizationMode mode) {
        IamAuthorizationService authorization = service(mode);
        IamPermissionCheck check = new IamPermissionCheck("storage.objects.create", IamResource.gcsObject("exports", "entry.json"));
        for (IamPrincipal principal : List.of(IamPrincipal.anonymous(), new IamPrincipal("user:caller@example.test"),
                new IamPrincipal("group:callers@example.test"))) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(GcpException.class,
                    () -> authorization.authorizeServicePrincipal(principal, check)).getGrpcCode());
        }
    }

    @Test
    void disabledModeAllowsAnInternalServiceAccountPrincipal() {
        IamAuthorizationService authorization = service(EmulatorConfig.IamAuthorizationMode.DISABLED);
        assertDoesNotThrow(() -> authorization.authorizeServicePrincipal(IamPrincipal.serviceAccount("logging@example.test"),
                new IamPermissionCheck("storage.objects.create", IamResource.gcsObject("exports", "entry.json"))));
    }

    private static IamAuthorizationService service(EmulatorConfig.IamAuthorizationMode mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().authorizationMode()).thenReturn(mode);
        return new IamAuthorizationService(config, mock(IamPrincipalResolver.class), mock(IamPolicyEvaluator.class),
                mock(IamResourceHierarchy.class), new IamRoleCatalog(), mock(IamConditionEvaluator.class),
                mock(IamService.class), mock(IamAuthorizationRegistry.class), mock(IamRequestIdentity.class));
    }
}

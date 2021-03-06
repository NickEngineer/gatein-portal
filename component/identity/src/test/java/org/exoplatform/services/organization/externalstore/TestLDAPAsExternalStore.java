package org.exoplatform.services.organization.externalstore;

import org.gatein.portal.idm.impl.repository.ExoFallbackIdentityStoreRepository;
import org.picketlink.idm.impl.api.session.IdentitySessionImpl;
import org.picketlink.idm.spi.repository.IdentityStoreRepository;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.component.test.*;
import org.exoplatform.services.organization.*;
import org.exoplatform.services.organization.idm.*;
import org.exoplatform.services.organization.idm.externalstore.PicketLinkIDMExternalStoreService;

import exo.portal.component.identiy.opendsconfig.opends.OpenDSService;

@ConfiguredBy({ @ConfigurationUnit(scope = ContainerScope.PORTAL, path = "conf/exo.portal.component.test.jcr-configuration.xml"),
    @ConfigurationUnit(scope = ContainerScope.PORTAL, path = "conf/exo.portal.component.identity-external-ldap-store-configuration.xml") })
public class TestLDAPAsExternalStore extends AbstractKernelTest {

  protected UserHandler                uHandler;

  OpenDSService                        openDSService = new OpenDSService(null);

  PicketLinkIDMOrganizationServiceImpl organizationService;

  PicketLinkIDMServiceImpl             idmService;

  PicketLinkIDMExternalStoreService    externalStoreService;

  protected void setUp() throws Exception {
    setForceContainerReload(true);

    organizationService =
                        (PicketLinkIDMOrganizationServiceImpl) getContainer().getComponentInstanceOfType(OrganizationService.class);
    idmService = (PicketLinkIDMServiceImpl) getContainer().getComponentInstanceOfType(PicketLinkIDMService.class);
    externalStoreService =
                         (PicketLinkIDMExternalStoreService) getContainer().getComponentInstanceOfType(IDMExternalStoreService.class);
    begin();
  }

  @Override
  protected void tearDown() throws Exception {
    end();
    super.tearDown();
  }

  @Override
  protected void beforeRunBare() {
    try {
      openDSService.start();
      openDSService.initLDAPServer();
    } catch (Exception e) {
      log.error("Error in starting up OPENDS", e);
      e.printStackTrace();
    }
    super.beforeRunBare();
  }

  public void testConfiguration() throws Exception {
    IdentityStoreRepository identityStoreRepository =
                                                    ((IdentitySessionImpl) idmService.getIdentitySession()).getSessionContext()
                                                                                                           .getIdentityStoreRepository();
    assertTrue(organizationService.getConfiguration().isCountPaginatedUsers());
    assertFalse(organizationService.getConfiguration().isSkipPaginationInMembershipQuery());

    assertTrue(identityStoreRepository instanceof ExoFallbackIdentityStoreRepository);

    assertTrue(organizationService.getConfiguration().isIgnoreMappedMembershipTypeForGroup("/organization_hierarchy/OrganizationD"));
    assertTrue(organizationService.getConfiguration().isIgnoreMappedMembershipTypeForGroup("/role_hierarchy/User"));
  }

  public void testStoreToUse() throws Exception {
    ExoFallbackIdentityStoreRepository exoFallbackIdentityStoreRepository = externalStoreService.getFallbackStoreRepository();
    assertEquals(exoFallbackIdentityStoreRepository.getIdentityStore(),
                 exoFallbackIdentityStoreRepository.getDefaultIdentityStore());
    exoFallbackIdentityStoreRepository.setUseExternalStore(true);
    try {
      assertEquals(exoFallbackIdentityStoreRepository.getIdentityStore(),
                   exoFallbackIdentityStoreRepository.getExternalIdentityStore());
    } finally {
      exoFallbackIdentityStoreRepository.setUseExternalStore(false);
    }
    assertEquals(exoFallbackIdentityStoreRepository.getIdentityStore(),
                 exoFallbackIdentityStoreRepository.getDefaultIdentityStore());
  }

  public void testAuthenticate() throws Exception {
    User user = organizationService.getUserHandler().findUserByName("jduke1");
    assertNull(user);

    ListAccess<User> allusers = organizationService.getUserHandler().findAllUsers();
    assertEquals(0, allusers.getSize());

    boolean authenticated = organizationService.getUserHandler().authenticate("jduke1", "test");
    assertFalse(authenticated);
    user = organizationService.getUserHandler().findUserByName("jduke1");
    assertNull(user);
    allusers = organizationService.getUserHandler().findAllUsers();
    assertEquals(0, allusers.getSize());

    authenticated = organizationService.getUserHandler().authenticate("jduke1", "theduke");
    assertTrue(authenticated);

    user = organizationService.getUserHandler().findUserByName("jduke1");
    assertNotNull(user);

    allusers = organizationService.getUserHandler().findAllUsers();
    assertEquals(1, allusers.getSize());
  }

}

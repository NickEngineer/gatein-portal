package org.exoplatform.portal.jdbc.migration;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.portal.pom.config.POMDataStorage;
import org.exoplatform.portal.pom.data.*;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.listener.ListenerService;

public class SiteMigrationService extends AbstractMigrationService {

  public static final String EVENT_LISTENER_KEY = "PORTAL_SITES_MIGRATION";

  public SiteMigrationService(InitParams initParams,
                              POMDataStorage pomStorage,
                              ModelDataStorage modelStorage,
                              ListenerService listenerService,
                              RepositoryService repoService,
                              SettingService settingService) {
    super(initParams, pomStorage, modelStorage, listenerService, repoService, settingService);
  }

  @Override
  public void doMigrate(PortalKey siteToMigrateKey) throws Exception {
    PortalData toMigrateSite = pomStorage.getPortalConfig(siteToMigrateKey);
    ContainerData portalLayoutContainer = this.migrateContainer(toMigrateSite.getPortalLayout());

    PortalData existingSite = modelStorage.getPortalConfig(siteToMigrateKey);
    String storageId = null;
    if (existingSite != null) {
      try {
        modelStorage.remove(existingSite);
        existingSite = null; // NOSONAR
      } catch (Throwable e) {
        log.warn("Unable to reimport site {}::{}, update it instead", existingSite.getName(), existingSite.getType(), e);
        storageId = existingSite.getStorageId();
      } finally {
        MigrationContext.restartTransaction();
      }
    }

    PortalData toMigratePortalSite = new PortalData(storageId,
                                        toMigrateSite.getName(),
                                        toMigrateSite.getType(),
                                        toMigrateSite.getLocale(),
                                        toMigrateSite.getLabel(),
                                        toMigrateSite.getDescription(),
                                        toMigrateSite.getAccessPermissions(),
                                        toMigrateSite.getEditPermission(),
                                        toMigrateSite.getProperties(),
                                        toMigrateSite.getSkin(),
                                        portalLayoutContainer,
                                        toMigrateSite.getRedirects());
    if (StringUtils.isBlank(storageId)) {
      modelStorage.create(toMigratePortalSite);
    } else {
      modelStorage.save(toMigratePortalSite);
    }

    existingSite = modelStorage.getPortalConfig(siteToMigrateKey);
    broadcastListener(existingSite, existingSite.getKey().toString());
  }

  @Override
  public void doRemove(PortalKey siteToMigrateKey) throws Exception {
    PortalData data = pomStorage.getPortalConfig(siteToMigrateKey);
    if (data != null) {
      pomStorage.remove(data);
    }
    pomStorage.save();
  }

  protected String getListenerKey() {
    return EVENT_LISTENER_KEY;
  }
}

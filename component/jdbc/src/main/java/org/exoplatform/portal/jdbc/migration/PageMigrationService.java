package org.exoplatform.portal.jdbc.migration;

import java.util.*;

import org.exoplatform.commons.persistence.impl.EntityManagerService;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.management.annotations.Managed;
import org.exoplatform.management.annotations.ManagedDescription;
import org.exoplatform.management.jmx.annotations.NameTemplate;
import org.exoplatform.management.jmx.annotations.Property;
import org.exoplatform.portal.mop.QueryResult;
import org.exoplatform.portal.mop.SiteKey;
import org.exoplatform.portal.mop.page.PageContext;
import org.exoplatform.portal.mop.page.PageKey;
import org.exoplatform.portal.mop.page.PageService;
import org.exoplatform.portal.mop.page.PageServiceImpl;
import org.exoplatform.portal.pom.config.POMDataStorage;
import org.exoplatform.portal.pom.data.ModelDataStorage;
import org.exoplatform.portal.pom.data.PageData;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.listener.ListenerService;

@Managed
@ManagedDescription("Portal migration pages from JCR to RDBMS.")
@NameTemplate({ @Property(key = "service", value = "portal"), @Property(key = "view", value = "migration-pages") })
public class PageMigrationService extends AbstractMigrationService<PageContext> {
  public static final String EVENT_LISTENER_KEY = "PORTAL_PAGES_MIGRATION";

  private ModelDataStorage   modelStorage;

  private PageService        pageService;

  private PageServiceImpl    jcrPageService;

  public PageMigrationService(InitParams initParams,
                              POMDataStorage pomStorage,
                              ModelDataStorage modelStorage,
                              PageService pageService,
                              PageServiceImpl jcrPageService,
                              ListenerService listenerService,
                              RepositoryService repoService,
                              EntityManagerService entityManagerService) {

    super(initParams, pomStorage, listenerService, repoService, entityManagerService);
    this.modelStorage = modelStorage;
    this.pageService = pageService;
    this.jcrPageService = jcrPageService;
    this.LIMIT_THRESHOLD = getInteger(initParams, LIMIT_THRESHOLD_KEY, 100);
  }

  @Override
  protected void beforeMigration() throws Exception {
    MigrationContext.setPageDone(false);
  }

  @Override
  @Managed
  @ManagedDescription("Manual to start run migration data of pages from JCR to RDBMS.")
  public void doMigration() {
    boolean begunTx = startTx();

    long t = System.currentTimeMillis();
    Set<String> failedPages = new HashSet<>();
    int offset = 0;

    try {
      LOG.info("| \\ START::pages migration ---------------------------------");
      QueryResult<PageContext> pages;
      do {
        pages = jcrPageService.findPages(offset, LIMIT_THRESHOLD, null, null, null, null);

        Iterator<PageContext> pageItr = pages.iterator();
        while (pageItr.hasNext()) {
          if (forkStop) {
            break;
          }
          offset++;
          PageContext page = pageItr.next();

          LOG.info(String.format("|  \\ START::page number: %s (%s page)", offset, page.getKey().format()));
          long t1 = System.currentTimeMillis();

          try {
            PageKey key = page.getKey();
            SiteKey siteKey = key.getSite();
            PageContext created = pageService.loadPage(key);

            org.exoplatform.portal.pom.data.PageKey pomPageKey =
                    new org.exoplatform.portal.pom.data.PageKey(siteKey.getTypeName(),
                            siteKey.getName(),
                            key.getName());
            PageData pageData = pomStorage.getPage(pomPageKey);

            if (created == null) {
              LOG.info("Creating page: {} already in JPA", key);
              pageService.savePage(page);
              PageData migrate = new PageData(
                      null,
                      pageData.getId(),
                      pageData.getName(),
                      pageData.getIcon(),
                      pageData.getTemplate(),
                      pageData.getFactoryId(),
                      pageData.getTitle(),
                      pageData.getDescription(),
                      pageData.getWidth(),
                      pageData.getHeight(),
                      pageData.getAccessPermissions(),
                      this.migrateComponents(pageData.getChildren()),
                      pageData.getOwnerType(),
                      pageData.getOwnerId(),
                      pageData.getEditPermission(),
                      pageData.isShowMaxWindow(),
                      pageData.getMoveAppsPermissions(),
                      pageData.getMoveContainersPermissions()
              );

              modelStorage.save(migrate);
            } else {
              LOG.info("Updating layout for page: {} already in JPA", created.getKey());
              PageData data = modelStorage.getPage(pomPageKey);
              PageData migrate = new PageData(
                      data.getStorageId(),
                      data.getId(),
                      data.getName(),
                      data.getIcon(),
                      data.getTemplate(),
                      data.getFactoryId(),
                      data.getTitle(),
                      data.getDescription(),
                      data.getWidth(),
                      data.getHeight(),
                      data.getAccessPermissions(),
                      this.migrateComponents(pageData.getChildren()),
                      data.getOwnerType(),
                      data.getOwnerId(),
                      data.getEditPermission(),
                      data.isShowMaxWindow(),
                      data.getMoveAppsPermissions(),
                      data.getMoveContainersPermissions()
              );
              modelStorage.save(migrate);
            }

            //
            if (offset % LIMIT_THRESHOLD == 0) {
              endTx(begunTx);
              RequestLifeCycle.end();
              RequestLifeCycle.begin(PortalContainer.getInstance());
              begunTx = startTx();
            }

            created = pageService.loadPage(page.getKey());
            broadcastListener(created, created.getKey().toString());

            LOG.info(String.format("|  / END::page number %s (%s page) consumed %s(ms)",
                                   offset,
                                   page.getKey(),
                                   System.currentTimeMillis() - t1));
          } catch (Exception ex) {
            LOG.error("Exception during migration page: " + page.getKey(), ex);
            failedPages.add(page.getKey().format());
          }
        }
      } while (pages.getSize() > 0);

    } finally {
      endTx(begunTx);
      RequestLifeCycle.end();
      MigrationContext.setPagesMigrateFailed(failedPages);
      RequestLifeCycle.begin(PortalContainer.getInstance());
      LOG.info(String.format("| / END::page migration for (%s) page(s) consumed %s(ms)", offset, System.currentTimeMillis() - t));
    }
  }

  @Override
  protected void afterMigration() throws Exception {
    if (forkStop || !MigrationContext.getPagesMigrateFailed().isEmpty()) {
      return;
    }
    MigrationContext.setPageDone(true);
  }

  public void doRemove() throws Exception {
    LOG.info("| \\ START::cleanup pages ---------------------------------");
    long t = System.currentTimeMillis();
    long timePerpage = System.currentTimeMillis();

    int offset = 0;
    QueryResult<PageContext> pages;

    do {
      RequestLifeCycle.begin(PortalContainer.getInstance());
      pages = jcrPageService.findPages(offset, LIMIT_THRESHOLD, null, null, null, null);

      try {
        Iterator<PageContext> pageItr = pages.iterator();
        while (pageItr.hasNext()) {
          PageContext page = pageItr.next();
          LOG.info(String.format("|  \\ START::cleanup page number: %s (%s page)", offset, page.getKey()));
          offset++;

          try {
            jcrPageService.destroyPage(page.getKey());

            LOG.info(String.format("|  / END::cleanup (%s page) consumed time %s(ms)",
                                   page.getKey(),
                                   System.currentTimeMillis() - timePerpage));

            timePerpage = System.currentTimeMillis();
          } catch (Exception ex) {
            LOG.error("Can't remove page", ex);
          }
        }
        LOG.info(String.format("| / END::cleanup pages migration for (%s) page consumed %s(ms)",
                               offset,
                               System.currentTimeMillis() - t));
      } finally {
        RequestLifeCycle.end();
      }
    } while (pages.getSize() > 0);
  }

  @Override
  @Managed
  @ManagedDescription("Manual to stop run miguration data of pages from JCR to RDBMS.")
  public void stop() {
    super.stop();
  }

  protected String getListenerKey() {
    return EVENT_LISTENER_KEY;
  }
}

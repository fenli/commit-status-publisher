package jetbrains.buildServer.swarm.commitPublisher;

import com.intellij.openapi.util.io.StreamUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import jetbrains.buildServer.buildTriggers.vcs.BuildBuilder;
import jetbrains.buildServer.commitPublisher.HttpPublisherTest;
import jetbrains.buildServer.commitPublisher.MockPluginDescriptor;
import jetbrains.buildServer.log.LogInitializer;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.swarm.SwarmClient;
import jetbrains.buildServer.swarm.SwarmClientManager;
import jetbrains.buildServer.util.cache.ResetCacheRegisterImpl;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.StringEntity;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author kir
 */
public class SwarmPublisherWithNativeSwarmTest extends HttpPublisherTest {

  private static final String CHANGELIST = "1234321";
  private boolean myCreatePersonal;
  private boolean myReviewsRequested;
  private boolean myPassUrlViaBuild;

  public SwarmPublisherWithNativeSwarmTest() {
    //System.setProperty("teamcity.dev.test.retry.count", "0");
    
    String updateUrl = "POST /api/v11/testruns/706/FAE4501C-E4BC-73E4-A11A-FF710601BC3F HTTP/1.1";
    String generalMessagesRegexp = "\"Build queued: [^\"]+\",\"Build started: [^\"]+\",";
    String urlParam = ",\"url\":\"http://localhost:8111/viewLog\\.html\\?buildId=\\d+&buildTypeId=MyDefaultTestBuildType\"";

    myExpectedRegExps.put(EventToTest.STARTED, "POST /api/v11/reviews/19/testruns HTTP/1.1\tENTITY:[\\s\\S]+\"url\":\\s*\"http://localhost:8111/viewLog.html\\?buildId=1[\\s\\S]+");
    
    myExpectedRegExps.put(EventToTest.PAYLOAD_ESCAPED, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Failure\"\\]" + urlParam + ",\"status\":\"fail\"}");

    myExpectedRegExps.put(EventToTest.FAILURE_DETECTED, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Problem description \\(new\\)\"]" + urlParam + ",\"status\":\"running\"}");
    myExpectedRegExps.put(EventToTest.INTERRUPTED, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Problem description \\(new\\)\"\\]" + urlParam + ",\"status\":\"fail\"}");

    myExpectedRegExps.put(EventToTest.MARKED_SUCCESSFUL, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Success\"\\]" + urlParam + ",\"status\":\"pass\"}");
    myExpectedRegExps.put(EventToTest.MARKED_RUNNING_SUCCESSFUL, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Running\"\\]" + urlParam + ",\"status\":\"running\"}");

    myExpectedRegExps.put(EventToTest.FAILED, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Failure\"\\]" + urlParam + ",\"status\":\"fail\"}");
    myExpectedRegExps.put(EventToTest.FINISHED, updateUrl + "\tENTITY: \\{\"messages\":\\["+ generalMessagesRegexp + "\"Status: Success\"\\]" + urlParam + ",\"status\":\"pass\"}");

  }

  @BeforeMethod
  @Override
  protected void setUp() throws Exception {
    super.setUp();

    LogInitializer.setUnitTest(true);

    SwarmClientManager clientManager = new SwarmClientManager(myWebLinks, () -> null, new ResetCacheRegisterImpl());
    myPublisherSettings = new SwarmPublisherSettings(new MockPluginDescriptor(), myWebLinks, myProblems, myTrustStoreProvider, clientManager);
    Map<String, String> params = getPublisherParams();
    myPublisher = new SwarmPublisher((SwarmPublisherSettings)myPublisherSettings, myBuildType, FEATURE_ID, params, myProblems, myWebLinks,
                                     clientManager.getSwarmClient(params));


    myBuildType.addParameter(new SimpleParameter("vcsRoot." + myVcsRoot.getExternalId() + ".shelvedChangelist", CHANGELIST));
    myCreatePersonal = true;

    myReviewsRequested = false;
  }

  protected SRunningBuild startBuildInCurrentBranch(SBuildType buildType) {
    return theBuild(buildType).run();
  }

  private BuildBuilder theBuild(SBuildType buildType) {
    final BuildBuilder result = build().in(buildType);
    if (myPassUrlViaBuild) {
      result.addTriggerParam(SwarmClient.SWARM_UPDATE_URL, getServerUrl() + "/api/v11/testruns/706/FAE4501C-E4BC-73E4-A11A-FF710601BC3F");
    }
    return myCreatePersonal ? result.personalForUser("fedor") : result;
  }

  protected SFinishedBuild createBuildInCurrentBranch(SBuildType buildType, Status status) {
    return status.isSuccessful() ? theBuild(buildType).finish() : theBuild(buildType).failed().finish();
  }

  @NotNull
  @Override
  protected SQueuedBuild addBuildToQueue() {
    return theBuild(myBuildType).addToQueue();
  }

  @Override
  protected Map<String, String> getPublisherParams() {
    return new HashMap<String, String>() {{
      put(SwarmPublisherSettings.PARAM_URL, getServerUrl());
      put(SwarmPublisherSettings.PARAM_USERNAME, "admin");
      put(SwarmPublisherSettings.PARAM_PASSWORD, "admin");
      put(SwarmPublisherSettings.PARAM_CREATE_SWARM_TEST, "true");
    }};
  }

  @Override
  protected boolean respondToGet(String url, HttpResponse httpResponse) {
    if (url.contains("/api/v9/reviews?fields=id,state,stateLabel&change[]=" + CHANGELIST)) {
      if (myReviewsRequested) {
        throw new AssertionError("Should not request reviews twice, should cache");
      }
      httpResponse.setEntity(new StringEntity("{\"lastSeen\":19,\"reviews\":[{\"id\":19,\"state\":\"needsReview\"}],\"totalCount\":1}", "UTF-8"));
      myReviewsRequested = true;
      return true;
    }
    if (url.contains("/api/v11/reviews/19/testruns")) {
      if (myPassUrlViaBuild) {
        throw new RuntimeException("This URL should not be called");
      }

      String responseJson = "perforce/sampleTestRunsResponse.json";
      try (InputStream stream = getClass().getClassLoader().getResourceAsStream(responseJson)) {
        String responseTemplate = new String(StreamUtil.loadFromStream(stream));

        // Have to use correct test name in the template
        String response = responseTemplate.replace("TESTNAME_VAR", myBuildType.getExternalId());
        httpResponse.setEntity(new StringEntity(response));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return true;
    }
    return false;
  }

  @Override
  protected boolean respondToPost(String url, String requestData, HttpRequest httpRequest, HttpResponse httpResponse) {
    if (url.contains("/api/v9/login")) {
      // Tested in SwarmPublisherTest
      return true;
    }

    // Add some comment to a review
    if (url.contains("/api/v9/comments") && requestData.contains("topic=reviews/19")) {
      httpResponse.setEntity(new StringEntity("{}", "UTF-8"));
      return true;
    }

    // Create test run call
    if (url.equals("/api/v11/reviews/19/testruns")) {
      httpResponse.setEntity(new StringEntity("{}", "UTF-8"));
      return true;
    }

    // Update existing test run call
    if (url.contains("/api/v11/testruns/706/FAE4501C-E4BC-73E4-A11A-FF710601BC3F")) {
      httpResponse.setEntity(new StringEntity("{}", "UTF-8"));
      return true;
    }
    return false;
  }

  public void test_pass_swarm_url_via_build() throws Exception {
    myPassUrlViaBuild = true;
    super.test_buildFinished_Successfully();
  }


  @Test(enabled = false)
  @Override
  public void test_buildQueued() throws Exception {
    super.test_buildQueued();
  }

  @Test(enabled = false)
  @Override
  public void test_buildRemovedFromQueue() throws Exception {
    super.test_buildRemovedFromQueue();
  }

  @Test(enabled = false)
  @Override
  public void should_report_timeout_failure() throws Exception {
    super.should_report_timeout_failure();
  }

  @Test(enabled = false)
  @Override
  public void test_testConnection() throws Exception {
    super.test_testConnection();
  }

  @Override
  @Test(enabled = false)
  public void test_testConnection_fails_on_readonly() throws InterruptedException {
    super.test_testConnection_fails_on_readonly();
  }

  @Override
  @Test(enabled = false)
  public void test_testConnection_fails_on_bad_repo_url() throws InterruptedException {
    super.test_testConnection_fails_on_bad_repo_url();
  }

  @Override
  @Test(enabled = false)
  public void test_testConnection_fails_on_missing_target() throws InterruptedException {
    super.test_testConnection_fails_on_missing_target();
  }

}

/*
 * Copyright 2000-2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.swarm.commitPublisher;

import com.intellij.openapi.util.io.StreamUtil;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import jetbrains.buildServer.buildTriggers.vcs.BuildBuilder;
import jetbrains.buildServer.commitPublisher.CommitStatusPublisher;
import jetbrains.buildServer.commitPublisher.HttpPublisherTest;
import jetbrains.buildServer.commitPublisher.MockPluginDescriptor;
import jetbrains.buildServer.commitPublisher.PublisherException;
import jetbrains.buildServer.log.LogInitializer;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.swarm.SwarmClient;
import jetbrains.buildServer.swarm.SwarmClientManager;
import jetbrains.buildServer.util.TestFor;
import jetbrains.buildServer.util.cache.ResetCacheRegisterImpl;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.StringEntity;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author kir
 */
public class SwarmPublisherWithNativeSwarmTest extends HttpPublisherTest {

  private static final String CHANGELIST = "1234321";
  private boolean myCreatePersonalBuild;
  private boolean myReviewsAlreadyRequested;
  private boolean myCreateTestRun;
  private String myReviewStatus;

  private boolean myTriggerBySwarm;
  private SwarmClientManager myClientManager;

  public SwarmPublisherWithNativeSwarmTest() {
    //System.setProperty("teamcity.dev.test.retry.count", "0");
    
    String updateUrl = "POST /api/v11/testruns/706/[0-9a-fA-F-]{36} HTTP/1.1";
    String generalMessagesRegexp = "\"Build queued: [^\"]+\",\"Build started: [^\"]+\",";
    String urlParam = ",\\s*\"url\":\\s*\"http://localhost:8111/buildConfiguration/MyDefaultTestBuildType/\\d+\"";

    // This is a message for "create test run" call
    myExpectedRegExps.put(EventToTest.STARTED, "POST /api/v11/reviews/19/testruns HTTP/1.1\tENTITY:[\\s\\S]+"+urlParam+"[\\s\\S]+");
    
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

    myCreateTestRun = false;   // by default, do not create Swarm Test Run in swarm
    myTriggerBySwarm = true;  // But expect swarmUpdateUrl passed via build triggering information

    myCreatePersonalBuild = true;
    myReviewsAlreadyRequested = false;

    myReviewStatus = "needsReview";

    myClientManager = new SwarmClientManager(myWebLinks, () -> null, new ResetCacheRegisterImpl());
    myPublisherSettings = new SwarmPublisherSettings(new MockPluginDescriptor(), myWebLinks, myProblems, myTrustStoreProvider, myClientManager);
    myBuildType.addParameter(new SimpleParameter("vcsRoot." + myVcsRoot.getExternalId() + ".shelvedChangelist", CHANGELIST));

    recreateSwarmPublisher();
  }

  private void recreateSwarmPublisher() {
    Map<String, String> params = getPublisherParams();
    myPublisher = new SwarmPublisher((SwarmPublisherSettings)myPublisherSettings, myBuildType, FEATURE_ID, params, myProblems, myWebLinks,
                                     myClientManager.getSwarmClient(params), EnumSet.allOf(CommitStatusPublisher.Event.class));
  }

  protected SRunningBuild startBuildInCurrentBranch(SBuildType buildType) {
    return theBuild(buildType).run();
  }

  private BuildBuilder theBuild(SBuildType buildType) {
    final BuildBuilder result = build().in(buildType);
    if (myTriggerBySwarm) {
      result.addTriggerParam(SwarmClient.SWARM_UPDATE_URL, "http://localhost/api/v11/testruns/706/FAE4501C-E4BC-73E4-A11A-FF710601BC3F");
    }
    return myCreatePersonalBuild ? result.personalForUser("fedor") : result;
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
      if (myCreateTestRun) {
        put(SwarmPublisherSettings.PARAM_CREATE_SWARM_TEST, "true");
      }
    }};
  }

  @Override
  protected boolean respondToGet(String url, HttpResponse httpResponse) {
    if (url.contains("/api/v9/reviews?fields=id,state,stateLabel&change[]=" + CHANGELIST)) {
      if (myReviewsAlreadyRequested) {
        throw new AssertionError("Should not request reviews twice, should cache");
      }
      httpResponse.setEntity(new StringEntity("{\"lastSeen\":19,\"reviews\":[{\"id\":19,\"state\":\"" + myReviewStatus +"\"}],\"totalCount\":1}", "UTF-8"));
      myReviewsAlreadyRequested = true;
      return true;
    }
    if (url.contains("/api/v11/reviews/19/testruns")) {
      if (myTriggerBySwarm) {
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
    if (url.equals("/api/v11/reviews/19/testruns") && myCreateTestRun) {
      httpResponse.setEntity(new StringEntity("{}", "UTF-8"));
      return true;
    }

    // Update existing test run call
    if (url.matches(".*/api/v11/testruns/706/[0-9a-fA-F-]{36}.*")) {
      httpResponse.setEntity(new StringEntity("{}", "UTF-8"));
      return true;
    }
    return false;
  }

  public void test_buildStarted() throws Exception {
    myTriggerBySwarm = false;
    myCreateTestRun = true;
    recreateSwarmPublisher();
    super.test_buildStarted();
  }

  public void test_buildStarted_update_test_run() throws Exception {
    myCreateTestRun = false;
    recreateSwarmPublisher();

    String toRestore = myExpectedRegExps.get(EventToTest.STARTED);

    try {
      myExpectedRegExps.put(EventToTest.STARTED, myExpectedRegExps.get(EventToTest.MARKED_RUNNING_SUCCESSFUL));
      super.test_buildStarted();
    } finally {
      myExpectedRegExps.put(EventToTest.STARTED, toRestore);
    }
  }

  @TestFor(issues = "TW-83006")
  public void test_start_and_finish_build_with_creating_test_run() throws Exception {
    // Check that TC creates a test run and updates this build at the end
    myTriggerBySwarm = false;
    myCreateTestRun = true;
    recreateSwarmPublisher();

    SRunningBuild build = startBuildInCurrentBranch(myBuildType);
    myPublisher.buildStarted(build, myRevision);
    then(getRequestAsString()).matches(myExpectedRegExps.get(EventToTest.STARTED));

    finishBuild(build, false);
    myReviewsAlreadyRequested = false;
    myPublisher.buildFinished(build.getBuildPromotion().getAssociatedBuild(), myRevision);

    then(getRequestAsString()).matches(myExpectedRegExps.get(EventToTest.FINISHED));
    then(getRequestAsString()).doesNotContain("FAE4501C-E4BC-73E4-A11A-FF710601BC3F");
  }

  @TestFor(issues = "TW-83006")
  public void test_start_and_finish_build_when_triggered_by_swarm() throws Exception {
    // Check that TC does not create another swarm test run when
    // build is triggered by Swarm, i.e. test run is already created
    myTriggerBySwarm = true;
    myCreateTestRun = true;
    recreateSwarmPublisher();

    SRunningBuild build = startBuildInCurrentBranch(myBuildType);
    myPublisher.buildStarted(build, myRevision);
    then(getRequestAsString()).matches(myExpectedRegExps.get(EventToTest.MARKED_RUNNING_SUCCESSFUL));

    finishBuild(build, false);
    myReviewsAlreadyRequested = false;
    myPublisher.buildFinished(build.getBuildPromotion().getAssociatedBuild(), myRevision);

    then(getRequestAsString()).matches(myExpectedRegExps.get(EventToTest.FINISHED));
    then(getRequestAsString()).contains("FAE4501C-E4BC-73E4-A11A-FF710601BC3F"); // From Swarm
  }

  @Test
  @TestFor(issues = "TW-85803")
  public void should_update_test_run_when_review_is_already_approved() throws Exception {
    myCreateTestRun = false;
    myReviewStatus = "approved";
    recreateSwarmPublisher();

    super.test_buildFinished_Successfully();
  }

  @Test
  @TestFor(issues = "TW-83006")
  public void should_not_create_test_run_when_review_is_already_closed() throws Exception {
    myTriggerBySwarm = false;
    myCreateTestRun = true;
    myReviewStatus = "approved";
    recreateSwarmPublisher();

    SRunningBuild build = startBuildInCurrentBranch(myBuildType);
    myPublisher.buildStarted(build, myRevision);
    then(getRequestAsString()).doesNotMatch(myExpectedRegExps.get(EventToTest.STARTED));

    finishBuild(build, false);
    myReviewsAlreadyRequested = false;
    myPublisher.buildFinished(build.getBuildPromotion().getAssociatedBuild(), myRevision);

    then(getRequestAsString()).doesNotMatch(myExpectedRegExps.get(EventToTest.FINISHED));
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
  public void should_publish_failure_on_failed_to_start_build() throws PublisherException {
    super.should_publish_failure_on_failed_to_start_build();
  }

  @Test(enabled = false)
  @Override
  public void should_publish_failure_on_canceled_build() throws PublisherException {
    super.should_publish_failure_on_canceled_build();
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

  @Override
  @Test(enabled = false)
  public void test_should_not_publish_queued_over_final_status() throws Exception {
    super.test_should_not_publish_queued_over_final_status();
  }
}

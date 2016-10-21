package router

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.client.{RequestPatternBuilder, WireMock}
import connector.CredentialRole
import play.api.libs.json.{JsValue, Json}
import play.api.test.FakeApplication
import support.page._
import support.stubs.{CommonStubs, StubbedFeatureSpec, TaxAccountUser}

import scala.collection.JavaConverters._
import scala.collection.mutable.{Map => mutableMap}

class RouterAuditTwoStepVerificationFeature extends StubbedFeatureSpec with CommonStubs {

  val rule = Map(
    "enrolments" -> "some-enrolment-category"
  )

  val locations = Map(
    "location-1.name" -> "some-location-1",
    "location-1.url" -> "/some-location-1",
    "location-2.name" -> "some-location-2",
    "location-2.url" -> "/some-location-2"
  )

  val additionalConfiguration = Map[String, Any](
    "auditing.consumer.baseUri.host" -> stubHost,
    "auditing.consumer.baseUri.port" -> stubPort,
    "business-tax-account.host" -> s"http://$stubHost:$stubPort",
    "company-auth.host" -> s"http://$stubHost:$stubPort",
    "contact-frontend.host" -> s"http://$stubHost:$stubPort",
    "personal-tax-account.host" -> s"http://$stubHost:$stubPort",
    "two-step-verification.host" -> s"http://$stubHost:$stubPort",
    "two-step-verification-required.host" -> s"http://$stubHost:$stubPort",
    "tax-account-router.host" -> "",
    "throttling.enabled" -> false,
    "mongodb.uri" -> s"mongodb://localhost:27017/$databaseName",
    "business-enrolments" -> "enr1,enr2",
    // The request timeout must be less than the value used in the wiremock stubs that use withFixedDelay to simulate network problems.
    "ws.timeout.request" -> 1000,
    "ws.timeout.connection" -> 500,
    "two-step-verification.enabled" -> true,
    "logger.application" -> "ERROR",
    "logger.connector" -> "ERROR",
    "some-enrolment-category" -> "enr3,enr4",
    "some-rule" -> rule,
    "two-step-verification.uplift-locations" -> "location-1",
    "locations" -> locations
  ) ++ Seq("auth", "cachable.short-lived-cache", "government-gateway", "sa", "user-details", "platform-analytics")
    .map(service => Map(
      s"microservice.services.$service.host" -> stubHost,
      s"microservice.services.$service.port" -> stubPort
    )).reduce(_ ++ _)

  override lazy val app = FakeApplication(additionalConfiguration = additionalConfiguration)

  feature("Router audit two step verification") {
    scenario("still don't know") {

      Given("a user logged in through Government Gateway not registered for 2SV")
      createStubs(TaxAccountUser(isRegisteredFor2SV = false))

      And("user is admin")
      stubUserDetails(credentialRole = Some(CredentialRole.User))

      And("the user has some active enrolments")
      val userEnrolments = stubActiveEnrolments("enr3", "enr4")

      val auditEventStub = stubAuditEvent()

      When("the user hits the router")
      go(RouterRootPath)

      Then("an audit event should be sent")
      verify(postRequestedFor(urlMatching("^/write/audit.*$")))

      And("the audit event raised should be the expected one")
      val expectedDetail = Json.obj(
        "userEnrolments" -> userEnrolments,
        "credentialRole" -> CredentialRole.User.toString
      )
      val expectedTransactionName = "no two step verification"
      verifyAuditEvent(auditEventStub, expectedDetail, expectedTransactionName)
    }

  }

  def toJson(map: mutableMap[String, String]) = Json.obj(map.map { case (k, v) => k -> Json.toJsFieldJsValueWrapper(v) }.toSeq: _*)

  def verifyAuditEvent(auditEventStub: RequestPatternBuilder, expectedDetail: JsValue, expectedTransactionName: String): Unit = {
    val loggedRequests = WireMock.findAll(auditEventStub).asScala.toList

    /*
    {
        "auditSource": "tax-account-router-frontend",
        "auditType": "TwoStepVerificationOutcome",
        "authId": "/auth/oid/5731aeb3140000e026a0d1c1", //- we can include more information from auth if we think its important and useful
        "detail": {
            "ruleApplied": "rule_sa",
            "userEnrolments": "\"[{\"key\":\"IR-SA\",\"identifiers\":[{\"key\":\"UTR\",\"value\":\"999902737\"}],\"state\":\"Activated\"},{\"key\":\"HMCE-VATDEC-ORG\",\"identifiers\":[{\"key\":\"VATRegNo\",\"value\":\"999902737\"}],\"state\":\"Activated\"}]\"",
            "credentialRole": "admin",
            "mandatory": true
        }
    }
     */


    val event = Json.parse(loggedRequests
      .filter(s => s.getBodyAsString.matches( """^.*"auditType"[\s]*\:[\s]*"TwoStepVerificationOutcome".*$""")).head.getBodyAsString)
    (event \ "tags" \ "transactionName").as[String] shouldBe expectedTransactionName
    (event \ "detail") shouldBe expectedDetail
  }
}
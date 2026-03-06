package com.example.demo;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.CreateTrailRequest;
import software.amazon.awssdk.services.cloudtrail.model.StartLoggingRequest;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import io.github.cdimascio.dotenv.*;

@Service
public class AmazonAPIcaller {

    private Dotenv dotenv;
    private EventBridgeClient client;
    private IamClient iam;
    private CloudTrailClient cloudTrailClient;

    public AmazonAPIcaller() {
        this.dotenv = Dotenv.load();
        this.client = EventBridgeClient.builder()
            .credentialsProvider(StaticCredentialsProvider.create(getCreds()))
            .region(Region.US_EAST_1)
            .build();
        this.iam = IamClient.builder()
            .credentialsProvider(StaticCredentialsProvider.create(getCreds()))
            .region(Region.AWS_GLOBAL)
            .build();
        this.cloudTrailClient = CloudTrailClient.builder()
            .credentialsProvider(StaticCredentialsProvider.create(getCreds()))
            .region(Region.US_EAST_1)
            .build();
    }

    private AwsBasicCredentials getCreds() {
        return AwsBasicCredentials.create(
            dotenv.get("key"),
            dotenv.get("secret")
        );
    }

    public void makeAPIrule(String backendURL) {

        // make a cloud trail for EventBridge to listen for
        createCloudTrail();

        // ── 1. Place the rule: tell AWS to listen for when any of the given events occur
        PutRuleResponse ruleResponse = putRule();
        System.out.println("✅ Rule: " + ruleResponse.ruleArn());

        // ── 2. Create connection: register an invented API key so AWS can auth itself to our backend
        String connectionArn = createConnectionArn();
        System.out.println("✅ Connection: " + connectionArn);

        // ── 3. Create API destination: tell EventBridge to call THIS backend URL when an event occurs
        String destinationArn = createDestinationArn(connectionArn, backendURL);
        System.out.println("✅ Destination: " + destinationArn);

        // ── 4. Create IAM role: give EventBridge a persona that is authorized to ping the backend
        String roleArn = createIAMrole();
        System.out.println("✅ Role: " + roleArn);

        // ── 5. Wire it all together: connect the rule to the destination using the role
        PutTargetsResponse targets = putTargets("event-listener", destinationArn, roleArn);
        System.out.printf("there were %d failures in attaching target to rule\n", targets.failedEntryCount());
    }

    // ── wire the rule to the destination using the role ───────────
    public PutTargetsResponse putTargets(String rule, String destinationArn, String roleArn) {
        return this.client.putTargets(
            PutTargetsRequest.builder()
                .rule(rule)
                .targets(Target.builder()
                    .id("spring-boot-backend")
                    .arn(destinationArn)
                    .roleArn(roleArn)
                    .build())
                .build()
        );
    }

    // ── create IAM role that gives EventBridge permission to call the backend ──
    public String createIAMrole() {

        // trust policy — defines WHO can wear this role (only EventBridge)
        String trustPolicy =
            "{\"Version\":\"2012-10-17\"," +
            "\"Statement\":[{" +
                "\"Effect\":\"Allow\"," +
                "\"Principal\":{\"Service\":\"events.amazonaws.com\"}," +
                "\"Action\":\"sts:AssumeRole\"" +
            "}]}";

        // permission policy — defines WHAT the role is allowed to do
        String permissionPolicy =
            "{\"Version\":\"2012-10-17\"," +
            "\"Statement\":[{" +
                "\"Effect\":\"Allow\"," +
                "\"Action\":\"events:InvokeApiDestination\"," +
                "\"Resource\":\"*\"" +
            "}]}";

        String roleArn;
        try {
            roleArn = iam.createRole(CreateRoleRequest.builder()
                .roleName("EventBridgeInvokeApiDestination")
                .assumeRolePolicyDocument(trustPolicy)
                .build()
            ).role().arn();

            iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName("EventBridgeInvokeApiDestination")
                .policyName("InvokeApiDestinationPolicy")
                .policyDocument(permissionPolicy)
                .build()
            );

        } catch (EntityAlreadyExistsException e) {
            // role already exists from a previous run — just fetch its ARN
            roleArn = iam.getRole(GetRoleRequest.builder()
                .roleName("EventBridgeInvokeApiDestination")
                .build()
            ).role().arn();
        }
        return roleArn;
    }

    // ── tell EventBridge WHERE to send the event (your backend URL) ──
    public String createDestinationArn(String connectionArn, String backendURL) {
        String destinationArn;
        try {
            // try to create it fresh
            destinationArn = client.createApiDestination(
                CreateApiDestinationRequest.builder()
                    .name("spring-boot-backend")
                    .connectionArn(connectionArn)
                    .invocationEndpoint(backendURL + "/aws/events")
                    .httpMethod(ApiDestinationHttpMethod.POST)
                    .build()
            ).apiDestinationArn();
            System.out.println("✅ Destination created");

        } catch (ResourceAlreadyExistsException e) {
            // already exists — UPDATE it with the current ngrok URL
            destinationArn = client.updateApiDestination(
                UpdateApiDestinationRequest.builder()
                    .name("spring-boot-backend")
                    .connectionArn(connectionArn)
                    .invocationEndpoint(backendURL + "/aws/events") 
                    .httpMethod(ApiDestinationHttpMethod.POST)
                    .build()
            ).apiDestinationArn();
            System.out.println("✅ Destination updated to: " + backendURL + "/aws/events");
        }
        return destinationArn;
    }

    // ── register the event pattern rule with EventBridge ──────────
    public PutRuleResponse putRule() {
        return client.putRule(
            PutRuleRequest.builder()
                .name("event-listener")
                .eventPattern(
                    "{" +
                    "\"source\":[\"aws.ec2\",\"aws.lambda\",\"aws.s3\"]," +
                    "\"detail-type\":[\"AWS API Call via CloudTrail\"]," +
                    "\"detail\":{" +
                        "\"eventName\":[\"RunInstances\",\"CreateFunction\",\"CreateBucket\"]" +
                    "}" +
                    "}"
                )
                .state(RuleState.ENABLED)
                .description("Fires when EC2, Lambda, or S3 resources are created")
                .build()
        );
    }

    // ── register API key so AWS can authenticate itself to the backend ──
    public String createConnectionArn() {
        String connectionArn;
        try {
            connectionArn = client.createConnection(
                CreateConnectionRequest.builder()
                    .name("authorization-request")
                    .authorizationType(ConnectionAuthorizationType.API_KEY)
                    .authParameters(CreateConnectionAuthRequestParameters.builder()
                        .apiKeyAuthParameters(
                            CreateConnectionApiKeyAuthRequestParameters.builder()
                                .apiKeyName("x-api-key")
                                .apiKeyValue(dotenv.get("x-api-key"))
                                .build())
                        .build())
                    .build()
            ).connectionArn();
        } catch (ResourceAlreadyExistsException e) {
            // connection already exists — just fetch its ARN
            connectionArn = client.describeConnection(
                DescribeConnectionRequest.builder()
                    .name("authorization-request")
                    .build()
            ).connectionArn();
        }
        return connectionArn;
    }


    public void createCloudTrail() {
        //  Create S3 client 
        S3Client s3 = S3Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(getCreds()))
            .region(Region.US_EAST_1)
            .build();

        String accountId = dotenv.get("accountId");
        String bucketName = "my-cloudtrail-logs-" + accountId;

        //  Create S3 bucket 
        try {
            s3.createBucket(CreateBucketRequest.builder()
                .bucket(bucketName)
                .build()
            );
            System.out.println("S3 bucket created: " + bucketName);
        } catch (Exception e) {
            System.out.println("Bucket already exists");
        }

        // ── Attach bucket policy so CloudTrail can write to it ─
        String bucketPolicy =
            "{\"Version\":\"2012-10-17\"," +
            "\"Statement\":[{" +
                "\"Sid\":\"AWSCloudTrailAclCheck\"," +
                "\"Effect\":\"Allow\"," +
                "\"Principal\":{\"Service\":\"cloudtrail.amazonaws.com\"}," +
                "\"Action\":\"s3:GetBucketAcl\"," +
                "\"Resource\":\"arn:aws:s3:::" + bucketName + "\"" +
            "},{" +
                "\"Sid\":\"AWSCloudTrailWrite\"," +
                "\"Effect\":\"Allow\"," +
                "\"Principal\":{\"Service\":\"cloudtrail.amazonaws.com\"}," +
                "\"Action\":\"s3:PutObject\"," +
                "\"Resource\":\"arn:aws:s3:::" + bucketName + "/AWSLogs/" + accountId + "/*\"," +
                "\"Condition\":{\"StringEquals\":{\"s3:x-amz-acl\":\"bucket-owner-full-control\"}}" +
            "}]}";

        s3.putBucketPolicy(PutBucketPolicyRequest.builder()
            .bucket(bucketName)
            .policy(bucketPolicy)
            .build()
        );
        System.out.println("✅ Bucket policy attached");

        //  Create the trail 
        try {
            cloudTrailClient.createTrail(CreateTrailRequest.builder()
                .name("my-trail")
                .s3BucketName(bucketName)
                .includeGlobalServiceEvents(true)
                .isMultiRegionTrail(false)
                .build()
            );
            System.out.println("✅ Trail created");
        } catch (Exception e) {
            System.out.println("ℹ️ Trail already exists");
        }

        //  Start logging 
        cloudTrailClient.startLogging(StartLoggingRequest.builder()
            .name("my-trail")
            .build()
        );
        System.out.println("✅ Trail logging started!");

        s3.close();
    }
}
# Integrating SonarQube as a pull request approver on AWS CodeCommit

In this project, our main goal is using AWS CodeCommit's pull request approver which is integrated with SonarQube. Approval rules act as a gate on your source code changes. Pull request which fail to satisfy the required approvals cannot be merged into your branches. CodeCommit launched the ability to create [_approval rule templates_](https://docs.aws.amazon.com/codecommit/latest/userguide/approval-rule-templates.html), which are rulesets that can automatically be applied to all pull requests created for one or more repositories in your AWS account. With templates, it becomes simple to create rules like “require one approver from my team” for any number of repositories in your AWS account.

## Requirements 
* Java 11 
* Maven
* SonarQube
* AWS EC2 (To use SonarQube server)
* AWS CodeBuild (To invoke SonarQube instance on build and reports)
* AWS CloudWatch (To listen pull request then invoke CodeBuild)
* AWS Secret Manager ( To store and provide your credentials)
* IAM ( roles for CodeBuild and CloudWatch)

## Design

The following diagram shows the flow of data, starting with a new or updated pull request on CodeCommit. CloudWatch Events listens for these events and invokes your CodeBuild project. The CodeBuild container clones your repository source commit, performs a Maven install, and invokes the quality analysis on SonarQube, using the credentials obtained from AWS Secrets Manager. When finished, CodeBuild leaves a comment on your pull request, and potentially approves your pull request.

<p align="center">
  <img src="https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/01-data-flow-diagram.png">
</p>

# INSTRUCTIONS
### Create SonarQube Server
* You can check my [repo](https://github.com/CC-chain/DevOps/tree/main/SonarQube) to how to install SonarQube server on AWS EC2 with Docker.

>#### Create a SonarQube User
> * After you installed the program succesfully, you need to go to **Administration** tab on your SonarQube instance.
> * Choose **Security**, then **Users**, as shown in the following screenshot.
<p align="center">
<img src="https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/02-sonarqube-administration.png">
</p>


### Create AWS resources
For this integration, you need to create some AWS resources:

-   AWS CodeCommit repository
-   AWS CodeBuild project
-   Amazon CloudWatch Events rule (to trigger builds when pull requests are created or updated)
-   IAM role (for CodeBuild to assume)
-   IAM role (for CloudWatch Events to assume and invoke CodeBuild)
-   AWS Secrets Manager secret (to store and manage your SonarQube user credentials)

I get an AWS CloudFormation template that it will give these resources and connections for you. I will give some comments on this template to understand the basics and logic behind this template.

```yaml
AWSTemplateFormatVersion: '2010-09-09' #it identifies the capabilities of the template. The latest template format version is `2010-09-09` and is currently the only valid value.
Description: 'Resource template for integrating SonarQube with AWS CodeCommit approval workflow'


Parameters: # to customize your templates. Parameters enable you to input custom values to your template each time you create or update a stack.
  CodeCommitRepositoryName: # Your initial repo name.
    Type: String
    Default: PullRequestApproverBlogDemo
  CodeCommitRepositoryDescription:
    Type: String
    Default: 'A Maven Java project with a SonarQube analysis integration'
  SonarQubeUserName: #SonarQube username that we create beforehand.
    Type: String
    Description: 'Username of SonarQube identity'
    MinLength: 3
    NoEcho: true
  SonarQubePassword: #SonarQube password that we create beforehand.
    Type: String
    Description: 'Password for SonarQube user identity'
    MinLength: 8
    NoEcho: true

Resources: # declares the AWS resources that you want to include in the stack
  CodeCommitRepository: # Creates a new, empty repository.
    Type: AWS::CodeCommit::Repository
    Properties:
      RepositoryName: !Ref CodeCommitRepositoryName # We used CodeCommitRepository parameter. 
     # !Ref intrinsic function to reference a parameter, and AWS CloudFormation uses the parameter's value to provision the stack. You can reference parameters from the  `Resources`  and  `Outputs`  sections of the same template.
      RepositoryDescription: !Ref CodeCommitRepositoryDescription
      Code: # Information about code to be committed.
        S3: # Information about the Amazon S3 bucket that contains the code that will be committed to the new repository.
          Bucket: codecommit-sonarqube-integration-blog-dec-2019 # The name of the Amazon S3 bucket that contains the ZIP file with the content that will be committed to the new repository. This can be specified using the name of the bucket in the AWS account.
          Key: PullRequestApproverBlogDemo.zip # The key to use for accessing the Amazon S3 bucket.

  CodeBuildProject: # resource configures how AWS CodeBuild builds your source code
    Type: AWS::CodeBuild::Project
    Properties:
      Artifacts: # is a property of the AWS CodeBuild Project resource that specifies output settings for artifacts generated by an AWS CodeBuild build.
        Type: NO_ARTIFACTS # The build project does not produce any build output.
      BadgeEnabled: true # Indicates whether AWS CodeBuild generates a publicly accessible URL for your project's build badge.
      Description: !Sub 'SonarQube analysis for repository ${CodeCommitRepositoryName}'
      Environment: 
        ComputeType: BUILD_GENERAL1_MEDIUM # The type of compute environment. This determines the number of CPU cores and memory the build environment uses.
        Type: LINUX_CONTAINER # The type of build environment to use for related builds. You should check this page (https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-properties-codebuild-project-environment.html) before you choose a compute type.
        Image: 'aws/codebuild/standard:2.0' 
        # The image tag or image digest that identifies the Docker image to use for this build project.
        EnvironmentVariables: # A set of environment variables to make available to builds for this build project.
          - Name: SONARQUBE_USER_CREDENTIAL_SECRET
            Value: !Ref SonarQubeUserSecret
      QueuedTimeoutInMinutes: 10 # The number of minutes a build is allowed to be queued before it times out.
      ServiceRole: !GetAtt CodeBuildRole.Arn # The ARN of the IAM role that enables AWS CodeBuild to interact with dependent AWS services on behalf of the AWS account.
# !GetAtt intrinsic function returns the value of an attribute from a resource in the template.
      Source: #The source code settings for the project, such as the source code's repository type and location.
        Type: CODECOMMIT # The type of repository that contains the source code to be built.
        Location: !GetAtt CodeCommitRepository.CloneUrlHttp # Information about the location of the source code to be built.
      TimeoutInMinutes: 10

  CodeBuildRole: # Creates a new role for your AWS account. For more information about roles, see (https://docs.aws.amazon.com/IAM/latest/UserGuide/WorkingWithRoles.html). For information about quotas for role names and the number of roles you can create, see (https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html) in the _IAM User Guide_.
    Type: AWS::IAM::Role
    Properties:
      AssumeRolePolicyDocument: # The trust policy that is associated with this role. Trust policies define which entities can assume the role. You can associate only one trust policy with a role
        Version: '2012-10-17' # The `Version` policy element specifies the language syntax rules that are to be used to process a policy.
       # `2012-10-17`. This is the current version of the policy language, and you should always include a  `Version`  element and set it to  `2012-10-17`. Otherwise, you cannot use features such as  [policy variables](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_variables.html)  that were introduced with this version.
        Statement: # The `Statement` element is the main element for a policy. This element is required. The `Statement` element can contain a single statement or an array of individual statements.
        - Action: # The `Action` element describes the specific action or actions that will be allowed or denied
            - sts:AssumeRole # Returns a set of temporary security credentials that you can use to access AWS resources that you might not normally have access to. It uses sts (# AWS Security Token Services).
          Effect: Allow # The `Effect` element is required and specifies whether the statement results in an allow or an explicit deny.
          Principal: # Use the  `Principal`  element in a resource-based JSON policy to specify the principal that is allowed or denied access to a resource. You must use the  `Principal`  element in (https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_identity-vs-resource.html).
            Service: # You can specify AWS services in the `Principal` element of a resource-based policy or in condition keys that support principals. A _service principal_ is an identifier for a service.
              - codebuild.amazonaws.com
      Path: / # The path to the role
      Policies: # Adds or updates an inline policy document that is embedded in the specified IAM role.
        - PolicyName: CodeBuildAccess # The friendly name (not ARN) identifying the policy.
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Action:
                - logs:* # Amazon CloudWatch Logs (service prefix: `logs`) provides service-specific resources, actions, and condition context keys for use in IAM permission policies. You can check it from this site (https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazoncloudwatchlogs.html)
                - codecommit:PostCommentForPullRequest # Posts a comment on the comparison between two commits.
                - codecommit:UpdatePullRequestApprovalState # Updates the structure of an approval rule created specifically for a pull request. For example, you can change the number of required approvers and the approval pool for approvers.
                Effect: Allow
                Resource: '*'
              - Action:
                - codecommit:GitPull # In CodeCommit, the `GitPull` IAM policy permissions apply to any Git client command where data is retrieved from CodeCommit, including **git fetch**, **git clone**, and so on.
                Effect: Allow
                Resource: !GetAtt CodeCommitRepository.Arn # When you pass the logical ID of this resource, the function returns the Amazon Resource Name (ARN) of the repository.
              - Action: 
                - secretsmanager:GetSecretValue # Retrieves the contents of the encrypted fields `SecretString` or `SecretBinary` from the specified version of a secret, whichever contains content.
                Effect: Allow
                Resource: !Ref SonarQubeUserSecret # It gets SonarQube username and password with SecretString

  PullRequestTriggerCodeBuildRule:
    Type: AWS::Events::Rule
    Properties: 
      Description: 'Rule to trigger build from CodeCommit pull request'
      EventPattern:
        source:
          - aws.codecommit
        detail-type:
          - 'CodeCommit Pull Request State Change'
        detail:
          event:
            - pullRequestCreated
            - pullRequestSourceBranchUpdated
        resources:
          - !GetAtt CodeCommitRepository.Arn
      State: ENABLED # The state of the rule.
      Targets: #Adds the specified targets to the specified rule, or updates the targets if they are already associated with the rule. Targets are the resources that are invoked when a rule is triggered.
        - Arn: !GetAtt CodeBuildProject.Arn # The Amazon Resource Name (ARN) of the target.
          Id: codebuild # The ID of the target within the specified rule. Use this ID to reference the target when updating the rule.
          RoleArn: !GetAtt CloudWatchEventsCodeBuildRole.Arn # The Amazon Resource Name (ARN) of the IAM role to be used for this target when the rule is triggered. If one rule triggers multiple targets, you can use a different IAM role for each target.
          InputTransformer: # Settings to enable you to provide custom input to a target based on certain event data. You can extract one or more key-value pairs from the event and then use that data to send customized input to the target.
          # Input template where you specify placeholders that will be filled with the values of the keys from `InputPathsMap` to customize the data sent to the target. Enclose each `InputPathsMaps` value in brackets: <_value_>
            InputTemplate:  |  
              {
                "sourceVersion": <sourceVersion>,
                "artifactsOverride": {"type": "NO_ARTIFACTS"},
                "environmentVariablesOverride": [
                   {
                       "name": "PULL_REQUEST_ID",
                       "value": <pullRequestId>,
                       "type": "PLAINTEXT"
                   },
                   {
                       "name": "REPOSITORY_NAME",
                       "value": <repositoryName>,
                       "type": "PLAINTEXT"
                   },
                   {
                       "name": "SOURCE_COMMIT",
                       "value": <sourceCommit>,
                       "type": "PLAINTEXT"
                   },
                   {
                       "name": "DESTINATION_COMMIT",
                       "value": <destinationCommit>,
                       "type": "PLAINTEXT"
                   },
                   {
                      "name" : "REVISION_ID",
                      "value": <revisionId>,
                      "type": "PLAINTEXT"
                   }
                ]
              }
            InputPathsMap: # Map of JSON paths to be extracted from the event. You can then insert these in the template in  `InputTemplate`  to produce the output you want to be sent to the target. `InputPathsMap`  is an array key-value pairs, where each value is a valid JSON path. You can have as many as 100 key-value pairs. You must use JSON dot notation, not bracket notation. The keys cannot start with "AWS."
              sourceVersion: "$.detail.sourceCommit"
              pullRequestId: "$.detail.pullRequestId"
              repositoryName: "$.detail.repositoryNames[0]"
              sourceCommit: "$.detail.sourceCommit"
              destinationCommit: "$.detail.destinationCommit"
              revisionId: "$.detail.revisionId"

  CloudWatchEventsCodeBuildRole:
    Type: AWS::IAM::Role # Creates a new role for your AWS account.
    Properties:
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal:
              Service:
                - events.amazonaws.com
            Action: sts:AssumeRole
      Path: /
      Policies:
        - PolicyName: events-invoke-codebuild
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Resource: !GetAtt CodeBuildProject.Arn
                Action: 
                  - codebuild:StartBuild

  SonarQubeUserSecret:
    Type: AWS::SecretsManager::Secret
    Properties:
      Description: 'SonarQube user credentials'
      # The intrinsic function `Fn::Sub` substitutes variables in an input string with values that you specify. In your templates, you can use this function to construct commands or outputs that include values that aren't available until you create or update a stack.
      SecretString: !Sub '{"username":"${SonarQubeUserName}","password":"${SonarQubePassword}"}'

  SonarQubeUserSecretResourcePolicy:
    Type: AWS::SecretsManager::ResourcePolicy # Attaches a resource-based permission policy to a secret
    Properties: 
      SecretId: !Ref SonarQubeUserSecret
      ResourcePolicy: 
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal:
              AWS: !GetAtt CodeBuildRole.Arn
            Action:
              - secretsmanager:GetSecretValue
            Resource: '*'
            Condition:
              'ForAnyValue:StringEquals':
                'secretsmanager:VersionStage': AWSCURRENT
```

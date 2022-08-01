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

![design](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/01-data-flow-diagram.png)

# INSTRUCTIONS
### Create SonarQube Server
* You can check my [repo](https://github.com/CC-chain/DevOps/tree/main/SonarQube) to how to install SonarQube server on AWS EC2 with Docker.

>#### Create a SonarQube User
> * After you installed the program succesfully, you need to go to **Administration** tab on your SonarQube instance.
> * Choose **Security**, then **Users**, as shown in the following screenshot.
> ![screenshot1](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/02-sonarqube-administration.png)

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

  CodeBuildProject:
    Type: AWS::CodeBuild::Project
    Properties:
      Artifacts:
        Type: NO_ARTIFACTS
      BadgeEnabled: true
      Description: !Sub 'SonarQube analysis for repository ${CodeCommitRepositoryName}'
      Environment: 
        ComputeType: BUILD_GENERAL1_MEDIUM
        Type: LINUX_CONTAINER
        Image: 'aws/codebuild/standard:2.0'
        EnvironmentVariables:
          - Name: SONARQUBE_USER_CREDENTIAL_SECRET
            Value: !Ref SonarQubeUserSecret
      QueuedTimeoutInMinutes: 10
      ServiceRole: !GetAtt CodeBuildRole.Arn
      Source: 
        Type: CODECOMMIT
        Location: !GetAtt CodeCommitRepository.CloneUrlHttp
      TimeoutInMinutes: 10

  CodeBuildRole:
    Type: AWS::IAM::Role
    Properties:
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
        - Action: 
            - sts:AssumeRole
          Effect: Allow
          Principal:
            Service: 
              - codebuild.amazonaws.com
      Path: /
      Policies:
        - PolicyName: CodeBuildAccess
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Action:
                - logs:*
                - codecommit:PostCommentForPullRequest
                - codecommit:UpdatePullRequestApprovalState
                Effect: Allow
                Resource: '*'
              - Action:
                - codecommit:GitPull
                Effect: Allow
                Resource: !GetAtt CodeCommitRepository.Arn
              - Action:
                - secretsmanager:GetSecretValue
                Effect: Allow
                Resource: !Ref SonarQubeUserSecret

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
      State: ENABLED
      Targets: 
        - Arn: !GetAtt CodeBuildProject.Arn
          Id: codebuild
          RoleArn: !GetAtt CloudWatchEventsCodeBuildRole.Arn
          InputTransformer:
            InputTemplate: |
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
            InputPathsMap:
              sourceVersion: "$.detail.sourceCommit"
              pullRequestId: "$.detail.pullRequestId"
              repositoryName: "$.detail.repositoryNames[0]"
              sourceCommit: "$.detail.sourceCommit"
              destinationCommit: "$.detail.destinationCommit"
              revisionId: "$.detail.revisionId"

  CloudWatchEventsCodeBuildRole:
    Type: AWS::IAM::Role
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
      SecretString: !Sub '{"username":"${SonarQubeUserName}","password":"${SonarQubePassword}"}'

  SonarQubeUserSecretResourcePolicy:
    Type: AWS::SecretsManager::ResourcePolicy
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

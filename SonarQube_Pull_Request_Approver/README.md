
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
1.  Download or copy the  [CloudFormation template from GitHub](https://github.com/CC-chain/DevOps/blob/main/SonarQube_Pull_Request_Approver/template.yaml)  and save it as  `template.yaml`  on your local computer.
2.  At the  [CloudFormation console](https://console.aws.amazon.com/cloudformation/home), choose  **Create Stack (with new resources)**.
3.  Choose  **Upload a template file**.
4.  Choose  **Choose file**  and select the  `template.yaml`  file you just saved.
5.  Choose  **Next**.
6.  Give your stack a name, optionally update the CodeCommit repository name and description, and paste in the username and password of the SonarQube user you created.
7.  Choose  **Next**.
8.  Review the stack options and choose  **Next**.
9.  On  **Step 4**, review your stack, acknowledge the required capabilities, and choose  **Create Stack**.
10.  Wait for the stack creation to complete before proceeding.
11.  Before leaving the AWS CloudFormation console, choose the  **Resources**  tab and note down the newly created CodeBuildRole’s  **Physical Id**, as shown in the following screenshot. You need this in the next step.
<p align="center">
<img src="https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/03-cloudformation-codebuild-role.png">
</p>

### Create Custom buildspec.yml
As I mentioned, I am using Java and Maven project so that I need to a custom buildspec file. A _buildspec_ is a collection of build commands and related settings, in YAML format, that CodeBuild uses to run a build. You can include a buildspec as part of the source code or you can define a buildspec when you create a build project.

```yaml
version: 0.2

phases:
  install:
    runtime-versions:
      java: openjdk11

    commands:
      - apt-get update -y
      - apt-get install -y maven
      - pip3 install --upgrade awscli

  pre_build:
    commands:
      - sonar_host_url="http://<instance-address>:<port-number>"
      - sonar_project_key="$REPOSITORY_NAME"
      - sonar_username=$(aws secretsmanager get-secret-value --secret-id $SONARQUBE_USER_CREDENTIAL_SECRET | jq -r '.SecretString' | jq -r '.username')
      - sonar_password=$(aws secretsmanager get-secret-value --secret-id $SONARQUBE_USER_CREDENTIAL_SECRET | jq -r '.SecretString' | jq -r '.password')
      - git checkout $SOURCE_COMMIT

  build:
    commands:
      - mvn install
      - result=$(mvn clean sonar:sonar -Dsonar.projectKey=$sonar_project_key -Dsonar.host.url=$sonar_host_url -Dsonar.login=$sonar_username -Dsonar.password=$sonar_password)
      - echo $result

  post_build:
    commands:
      - sonar_link=$(echo $result | egrep -o "you can browse http://[^, ]+")
      - sonar_task_id=$(echo $result | egrep -o "task\?id=[^ ]+" | cut -d'=' -f2)
      - | # Allow time for SonarQube Background Task to complete
        stat="PENDING";
        while [ "$stat" != "SUCCESS" ]; do
          if [ $stat = "FAILED" ] || [ $stat = "CANCELLED" ]; then
            echo "SonarQube task $sonar_task_id failed";
            exit 1;
          fi
          stat=$(curl -u "$sonar_username:$sonar_password" $sonar_host_url/api/ce/task\?id=$sonar_task_id | jq -r '.task.status');
          echo "SonarQube analysis status is $stat";
          sleep 5;
        done
      - sonar_analysis_id=$(curl -u "$sonar_username:$sonar_password" $sonar_host_url/api/ce/task\?id=$sonar_task_id | jq -r '.task.analysisId')
      - quality_status=$(curl -u "$sonar_username:$sonar_password" $sonar_host_url/api/qualitygates/project_status\?analysisId=$sonar_analysis_id | jq -r '.projectStatus.status')
      - |
        if [ $quality_status = "ERROR" ]; then
          content=$(echo "SonarQube analysis complete. Quality Gate Failed.\n\nTo see why, $sonar_link");
        elif [ $quality_status = "OK" ]; then
          content=$(echo "SonarQube analysis complete. Quality Gate Passed.\n\nFor details, $sonar_link");
          aws codecommit update-pull-request-approval-state --pull-request-id $PULL_REQUEST_ID --approval-state APPROVE --revision-id $REVISION_ID;
        else
          content="An unexpected error occurred while attempting to analyze with SonarQube.";
        fi
      - aws codecommit post-comment-for-pull-request --pull-request-id $PULL_REQUEST_ID --repository-name $REPOSITORY_NAME --before-commit-id $DESTINATION_COMMIT --after-commit-id $SOURCE_COMMIT --content "$content"

artifacts:
  files: '**/*'
```

### Create an Approval Rule Template

Now that your resources are created, create an Approval Rule Template in the CodeCommit console. This template allows you to define a required approver for new pull requests on specific repositories.

1.  On the  [CodeCommit console home](http://console.aws.amazon.com/codecommit/home)  page, choose  **Approval rule templates**  in the left panel. Choose  **Create template**.
2.  Give the template a name (like  `Require SonarQube approval`) and optionally, a description.
3.  Set the number of approvals needed as  **1**.
4.  Under  **Approval pool members**, choose  **Add**.
5.  Set the approver type to  **Fully qualified ARN**. Since the approver will be the identity obtained by assuming the CodeBuild execution role, your approval pool ARN should be the following string:  
    `arn:aws:sts::<Your AccountId>:assumed-role/<Your CodeBuild IAM role name>/*`  
    The CodeBuild IAM role name is the Physical Id of the role you created and noted down above. You can also find the full name either in the  [IAM console](https://console.aws.amazon.com/iam/home)  or the AWS CloudFormation stack details. Adding this role to the approval pool allows any identity assuming your CodeBuild role to satisfy this approval rule.
6.  Under  **Associated repositories**, find and choose your repository (`PullRequestApproverBlogDemo`). This ensures that any pull requests subsequently created on your repository will have this rule by default.
7.  Choose  **Create**.

### **Update the repository with a SonarQube endpoint URL**

For this step, you update your CodeCommit repository code to include the endpoint URL of your SonarQube instance. This allows CodeBuild to know where to go to invoke your SonarQube.

You can use the  [AWS Management Console](https://aws.amazon.com/console/)  to make this code change.

1.  Head back to the CodeCommit home page and choose your repository name from the  **Repositories**  list.
2.  You need a new branch on which to update the code. From the repository page, choose  **Branches**, then  **Create branch**.
3.  Give the new branch a name (such as  `update-url`) and make sure you are branching from master. Choose  **Create branch**.
4.  You should now see two branches in the table. Choose the name of your new branch (`update-url`) to start browsing the code on this branch. On the  `update-url`  branch, open the  `buildspec.yml`  file by choosing it.
5.  Choose  **Edit**  to make a change.
6.  In the  `pre_build`  steps, modify line 17 with your SonarQube instance url and listen port number, as shown in the following screenshot.![Screenshot showing buildspec yaml code.](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/04-pre-build-line-edit.png)
7.  To save, scroll down and fill out the author, email, and commit message. When you’re happy, commit this by choosing  **Commit changes**.

### Create a Pull Request

You are now ready to create a pull request!

1.  From the CodeCommit console main page, choose  **Repositories**  and  **PullRequestApproverBlogDemo**.
2.  In the left navigation panel, choose  **Pull Requests**.
3.  Choose  **Create pull request**.
4.  Select  `master`  as your destination branch, and your new branch (`update-url`) as the source branch.
5.  Choose  **Compare**.
6.  Give your pull request a title and description, and choose  **Create pull request**.

It’s time to see the magic in action. Now that you’ve created your pull request, you should already see that your pull request requires one approver but is not yet approved. This rule comes from the template you created and associated earlier.

You’ll see images like the following screenshot if you browse through the tabs on your pull request:

![Screenshot showing that your pull request has 0 of 1 rule satisfied, with 0 approvals.](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/05-pull-request-not-satisfied.png)  ![Screenshot showing a table of approval rules on this pull request which were applied by a template. Require SonarQube approval is listed but not yet satisfied.](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/06-sonarqube-rule-not-satisfied.png)

Thanks to the CloudWatch Events Rule, CodeBuild should already be hard at work cloning your repository, performing a build, and invoking your SonarQube instance. It is able to find the SonarQube URL you provided because CodeBuild is cloning the source branch of your pull request. If you choose to peek at your project in the  [CodeBuild console](https://console.aws.amazon.com/codebuild/home), you should see an in-progress build.

Once the build has completed, head back over to your CodeCommit pull request page. If all went well, you’ll be able to see that SonarQube approved your pull request and left you a comment. (Or alternatively, failed and also left you a comment while not approving).

The  **Activity**  tab should resemble that in the following screenshot:

![Screenshot showing that a comment was made by SonarQube through CodeBuild, and that the quality gate passed. The comment includes a link back to the SonarQube instance.](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/07-activity-tab.png)

The  **Approvals**  tab should resemble that in the following screenshot:

![Screenshot of Approvals tab on the pull request. The approvals table shows an approval by the SonarQube and that the rule to require SonarQube approval is satisfied.](https://d2908q01vomqb2.cloudfront.net/7719a1c782a1ba91c031a682a0a2f8658209adbf/2019/12/11/08-approvals-tab.png)

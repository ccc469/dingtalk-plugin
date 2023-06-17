package io.jenkins.plugins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Cause.RemoteCause;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Cause.UserIdCause;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.context.PipelineEnvContext;
import io.jenkins.plugins.enums.BuildStatusEnum;
import io.jenkins.plugins.enums.MsgTypeEnum;
import io.jenkins.plugins.enums.NoticeOccasionEnum;
import io.jenkins.plugins.model.BuildExecutor;
import io.jenkins.plugins.model.BuildJobModel;
import io.jenkins.plugins.model.ButtonModel;
import io.jenkins.plugins.model.MessageModel;
import io.jenkins.plugins.service.impl.DingTalkServiceImpl;
import io.jenkins.plugins.tools.DingTalkUtils;
import io.jenkins.plugins.tools.Logger;
import io.jenkins.plugins.tools.Utils;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 所有项目触发
 *
 * @author liuwei
 */
@Slf4j
@Extension
public class DingTalkRunListener extends RunListener<Run<?, ?>> {

    private final DingTalkServiceImpl service = new DingTalkServiceImpl();

    private final String rootPath = Jenkins.get().getRootUrl();

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        DingTalkGlobalConfig globalConfig = DingTalkGlobalConfig.getInstance();
        log.info("全局配置信息，{}", Utils.toJson(globalConfig));
        this.send(run, listener, NoticeOccasionEnum.START);
    }

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        Result result = run.getResult();
        NoticeOccasionEnum noticeOccasion = getNoticeOccasion(result);
        try {
            this.send(run, listener, noticeOccasion);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("发送消息时报错: ", e);
        } finally {
            // 重置环境变量
            PipelineEnvContext.reset();
        }
    }

    private NoticeOccasionEnum getNoticeOccasion(Result result) {
        if (Result.SUCCESS.equals(result)) {
            return NoticeOccasionEnum.SUCCESS;
        }
        if (Result.FAILURE.equals(result)) {
            return NoticeOccasionEnum.FAILURE;
        }
        if (Result.ABORTED.equals(result)) {
            return NoticeOccasionEnum.ABORTED;
        }
        if (Result.UNSTABLE.equals(result)) {
            return NoticeOccasionEnum.UNSTABLE;
        }
        if (Result.NOT_BUILT.equals(result)) {
            return NoticeOccasionEnum.NOT_BUILT;
        }
        return null;
    }

    private BuildStatusEnum getBuildStatus(NoticeOccasionEnum noticeOccasion) {
        switch (noticeOccasion) {
            case START:
                return BuildStatusEnum.START;
            case SUCCESS:
                return BuildStatusEnum.SUCCESS;
            case FAILURE:
                return BuildStatusEnum.FAILURE;
            case ABORTED:
                return BuildStatusEnum.ABORTED;
            case UNSTABLE:
                return BuildStatusEnum.UNSTABLE;
            case NOT_BUILT:
                return BuildStatusEnum.NOT_BUILT;
            default:
                return BuildStatusEnum.UNKNOWN;
        }
    }

    private BuildExecutor getExecutorFromUser(Run<?, ?> run, TaskListener listener) {
        UserIdCause userIdCause = run.getCause(UserIdCause.class);

        if (userIdCause != null && userIdCause.getUserId() != null) {
            User user = User.getById(userIdCause.getUserId(), false);
            if (user != null) {
                String name = user.getDisplayName();
                String mobile = user.getProperty(DingTalkUserProperty.class).getMobile();
                if (StringUtils.isEmpty(mobile)) {
                    log.info(
                            "用户【{}】暂未设置手机号码，请前往 {} 添加。",
                            name,
                            user.getAbsoluteUrl() + "/configure"
                    );
                }
                return new BuildExecutor(name, mobile);
            }
        }

        return null;
    }

    private BuildExecutor getExecutorFromRemote(Run<?, ?> run) {
        RemoteCause remoteCause = run.getCause(RemoteCause.class);

        if (remoteCause != null) {
            return new BuildExecutor(
                    String.format("%s %s", remoteCause.getAddr(), remoteCause.getNote()),
                    null
            );
        }

        return null;
    }

    private BuildExecutor getExecutorFromUpstream(Run<?, ?> run, TaskListener listener) {
        UpstreamCause upstreamCause = run.getCause(UpstreamCause.class);
        if (upstreamCause != null) {
            Job<?, ?> job = Jenkins.get()
                    .getItemByFullName(upstreamCause.getUpstreamProject(), Job.class);
            if (job != null) {
                Run<?, ?> upstream = job.getBuildByNumber(upstreamCause.getUpstreamBuild());
                if (upstream != null) {
                    return this.getExecutor(upstream, listener);
                }
            }
            return new BuildExecutor(upstreamCause.getUpstreamProject(), null);
        }
        return null;
    }

    private BuildExecutor getExecutorFromBuild(Run<?, ?> run) {
        return new BuildExecutor(
                run.getCauses()
                        .stream()
                        .map(Cause::getShortDescription)
                        .collect(Collectors.joining()),
                null
        );
    }

    /**
     * @see <a
     * href="https://github.com/jenkinsci/build-user-vars-plugin/blob/master/src/main/java/org/jenkinsci/plugins/builduser/BuildUser.java">...</a>
     */
    private BuildExecutor getExecutor(Run<?, ?> run, TaskListener listener) {
        BuildExecutor executor = getExecutorFromUser(run, listener);
        if (executor == null) {
            executor = getExecutorFromRemote(run);
        }
        if (executor == null) {
            executor = getExecutorFromUpstream(run, listener);
        }
        if (executor == null) {
            executor = getExecutorFromBuild(run);
        }
        return executor;
    }


    private EnvVars getEnvVars(Run<?, ?> run, TaskListener listener) {
        EnvVars jobEnvVars;
        try {
            jobEnvVars = run.getEnvironment(listener);
        } catch (Exception e) {
            jobEnvVars = new EnvVars();
            e.printStackTrace();
            log.error("获取 job 任务的环境变量时发生异常");
            Thread.currentThread().interrupt();
        }
        try {
            EnvVars pipelineEnvVars = PipelineEnvContext.get();
            jobEnvVars.overrideAll(pipelineEnvVars);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("获取 pipeline 环境变量时发生异常");
        }
        return jobEnvVars;
    }

    private boolean skip(TaskListener listener, NoticeOccasionEnum noticeOccasion,
                         DingTalkNotifierConfig notifierConfig) {
        String stage = noticeOccasion.name();
        Set<String> noticeOccasions = notifierConfig.getNoticeOccasions();
        if (noticeOccasions.contains(stage)) {
            return false;
        }
        log.info("机器人 {} 已跳过 {} 环节", notifierConfig.getRobotName(), stage);
        return true;
    }


    private void send(Run<?, ?> run, TaskListener listener, NoticeOccasionEnum noticeOccasion) {
        Job<?, ?> job = run.getParent();
        DingTalkJobProperty property = job.getProperty(DingTalkJobProperty.class);

        if (property == null) {
            DingTalkUtils.log("当前任务未配置机器人，已跳过");
            return;
        }
        // 环境变量
        EnvVars envVars = getEnvVars(run, listener);

        // 执行人信息
        BuildExecutor executor = getExecutor(run, listener);
        String executorName = envVars.get("EXECUTOR_NAME", executor.getName());
        String executorMobile = envVars.get("EXECUTOR_MOBILE", executor.getMobile());

        // 项目信息
        String projectName = job.getFullDisplayName();
        String projectUrl = job.getAbsoluteUrl();

        // 构建信息
        String jobName = run.getDisplayName();
        String jobUrl = rootPath + run.getUrl();
        String duration = run.getDurationString();
        String buildTime = new SimpleDateFormat("yyyy年MM月dd HH时mm分ss秒").format(new Date());
        BuildStatusEnum statusType = getBuildStatus(noticeOccasion);

        // 设置环境变量
        envVars.put("EXECUTOR_NAME", executorName == null ? "" : executorName);
        envVars.put("EXECUTOR_MOBILE", executorMobile == null ? "" : executorMobile);
        envVars.put("PROJECT_NAME", projectName);
        envVars.put("PROJECT_URL", projectUrl);
        envVars.put("JOB_NAME", jobName);
        envVars.put("JOB_URL", jobUrl);
        envVars.put("JOB_DURATION", duration);
        envVars.put("JOB_STATUS", statusType.getLabel());
        envVars.put("BUILD_TIME", buildTime);


        // 项目目录
        String projectDir = job.getRootDir().getAbsolutePath().replace("jobs", "workspace");
        envVars.put("PROJECT_DIR", projectDir);

        try {
            // 获取git提交信息
            String[] gitPullCmd;
            if (envVars.containsKey("gitlabBranch")) {
                envVars.put("GIT_BRANCH", envVars.get("gitlabBranch"));
                gitPullCmd = new String[]{"/bin/sh", "-c", " cd " + projectDir + " && git pull origin " + envVars.get("gitlabBranch")};
            } else {
                gitPullCmd = new String[]{"/bin/sh", "-c", " cd " + projectDir + " && git pull"};
            }
            // 此处需要拉取一下代码, 才能获取到最新的提交信息
            execCommandWithPrintOut(gitPullCmd);

            Map<GitStats, String> gitStats = getGitStats(projectDir);
            DingTalkUtils.log("{} gitStats: {}", statusType.getLabel(), gitStats);
            envVars.put("GIT_AUTHOR", gitStats.get(GitStats.Author));
            envVars.put("GIT_AT", gitStats.get(GitStats.At));
            envVars.put("GIT_AT_TIME", gitStats.get(GitStats.AtTime));
            envVars.put("GIT_COMMIT_ID", gitStats.get(GitStats.CommitId));
            envVars.put("GIT_SHORT_COMMIT_ID", gitStats.get(GitStats.ShortCommitId));
            envVars.put("GIT_MESSAGE", gitStats.get(GitStats.Message));
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ButtonModel> btns = Utils.createDefaultBtns(jobUrl);
        List<String> result = new ArrayList<>();
        List<DingTalkNotifierConfig> notifierConfigs = property.getAvailableNotifierConfigs();
        DingTalkNotifierConfig.DingTalkNotifierConfigDescriptor descriptor = Jenkins.get().getDescriptorByType(DingTalkNotifierConfig.DingTalkNotifierConfigDescriptor.class);
        String defaultContent = descriptor.getDefaultContent();

        for (DingTalkNotifierConfig item : notifierConfigs) {
            boolean skipped = skip(listener, noticeOccasion, item);

            if (skipped) {
                continue;
            }

            String robotId = item.getRobotId();
            String content = StringUtils.isEmpty(item.getContent()) ? defaultContent : item.getContent();
            String message = item.getMessage();
            boolean atAll = item.isAtAll();
            Set<String> atMobiles = item.resolveAtMobiles(envVars);

            if (StringUtils.isNotEmpty(executorMobile)) {
                atMobiles.add(executorMobile);
            }

            MessageModel msgModel =
                    item.isRaw() ? MessageModel.builder()
                            .type(MsgTypeEnum.MARKDOWN)
                            .text(
                                    envVars.expand(message).replace("\\\\n", "\n")
                            ).build()
                            : MessageModel.builder()
                            .type(MsgTypeEnum.ACTION_CARD)
                            .atAll(atAll)
                            .atMobiles(atMobiles)
                            .title(
                                    String.format("%s %s", projectName, statusType.getLabel())
                            )
                            .text(
                                    BuildJobModel.builder().projectName(projectName).projectUrl(projectUrl)
                                            .buildTime(buildTime)
                                            .jobName(jobName)
                                            .jobUrl(jobUrl)
                                            .statusType(statusType)
                                            .duration(duration)
                                            .executorName(executorName)
                                            .executorMobile(executorMobile)
                                            .content(
                                                    envVars.expand(content).replace("\\\\n", "\n")
                                            )
                                            .build()
                                            .toMarkdown()
                            )
                            .btns(btns).build();

            DingTalkUtils.log("{} 当前机器人信息，\n{}", statusType.getLabel(), Utils.toJson(item));
            DingTalkUtils.log("{} 发送的消息详情，\n{}", statusType.getLabel(), Utils.toJson(msgModel));

            String msg = service.send(robotId, msgModel);
            if (msg != null) {
                result.add(msg);
            }
        }

        if (!result.isEmpty()) {
            result.forEach(msg -> Logger.error(listener, msg));
        }
    }

    private enum GitStats {
        /**
         * 完整Hash
         */
        CommitId,
        /**
         * 简短Hash
         */
        ShortCommitId,
        /**
         * 作者
         */
        Author,
        /**
         * 提交时间
         */
        At,
        /**
         * 提交时间
         */
        AtTime,
        /**
         * 提交信息
         */
        Message,
    }

    /**
     * 获取git提交信息
     *
     * @return
     */

    private static Map<GitStats, String> getGitStats(String projectDir) {
        String[] commitIdCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%H' --date=format:'%Y-%m-%d %H:%M:%S' HEAD -1"};
        String[] shortCommitCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%h' --date=format:'%Y-%m-%d %H:%M:%S' HEAD -1"};
        String[] authorCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%an' --date=format:'%Y-%m-%d %H:%M:%S' HEAD -1"};
        String[] atCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%ar' --date=format:'%Y-%m-%d %H:%M:%S' HEAD -1"};
        String[] atTimeCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%ad' --date=format:'%Y-%m-%d %H:%M:%S' HEAD -1"};
        String[] messageCmd = {"/bin/sh", "-c", " cd " + projectDir + " && git log --pretty=format:'%s%B' HEAD -1"};
        return new HashMap<GitStats, String>() {{
            put(GitStats.CommitId, execCommand(commitIdCmd));
            put(GitStats.ShortCommitId, execCommand(shortCommitCmd));
            put(GitStats.Author, execCommand(authorCmd));
            put(GitStats.At, execCommand(atCmd));
            put(GitStats.AtTime, execCommand(atTimeCmd));
            put(GitStats.Message, execCommandReplaceOut(messageCmd, "\n", "<br>"));
        }};
    }

    /**
     * 执行命令并得到返回结果
     *
     * @param command
     * @return
     */
    private static String execCommand(String[] command) {
        StringBuilder output = new StringBuilder();
        try {
            Process ps = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return output.toString().replaceAll("\n", "");
    }

    private static String execCommandWithPrintOut(String[] command) {
        StringBuilder output = new StringBuilder();
        try {
            Process ps = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return output.toString();
    }

    private static String execCommandReplaceOut(String[] command, String from, String to) {
        StringBuilder output = new StringBuilder();
        try {
            Process ps = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return output.toString().replaceAll(from, to);
    }
}

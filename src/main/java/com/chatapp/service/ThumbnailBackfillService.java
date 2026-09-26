package com.chatapp.service;

import com.chatapp.entity.Message;
import com.chatapp.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 给历史明文图片消息补/换预览图（聊天图片、AI 画图）：没有缩略图的补上，1.1.51 的 400px 老缩略图换成
 * 当前尺寸（{@link ImageThumbnailService#LONG_EDGE}），大原图顺带做中图。
 *
 * 幂等：按 messages.rendition_version 判断，处理过（包括做不出来的）都记上当前版本，不再是候选；
 * 同一个原图的转发副本共用一套预览图，一起换。换完后老缩略图文件没有任何消息再引用就删掉。
 * 有上限：每次最多看 {@code chat.thumbnails.backfill-max} 条，没看完的下次启动接着来。
 * 启动完成后在后台线程跑，不拖慢启动；{@code chat.thumbnails.backfill-on-startup=false} 可关掉。
 * 加密附件服务器看不到图，不在范围内（新发的由发送端做新尺寸，老的保持原样）。
 */
@Service
@Slf4j
public class ThumbnailBackfillService {
    private static final List<Message.MessageType> IMAGE_TYPES =
            List.of(Message.MessageType.IMAGE, Message.MessageType.IMAGE_GENERATION);
    private static final int BATCH_SIZE = 50;

    private final MessageRepository messageRepository;
    private final FileStorageService fileStorageService;
    private final ImageThumbnailService imageThumbnailService;
    private final TransactionTemplate transactionTemplate;
    private final Executor taskExecutor;
    private final boolean runOnStartup;
    private final int maxPerRun;

    public ThumbnailBackfillService(
            MessageRepository messageRepository,
            FileStorageService fileStorageService,
            ImageThumbnailService imageThumbnailService,
            TransactionTemplate transactionTemplate,
            @Qualifier("taskExecutor") Executor taskExecutor,
            @Value("${chat.thumbnails.backfill-on-startup:true}") boolean runOnStartup,
            @Value("${chat.thumbnails.backfill-max:1000}") int maxPerRun) {
        this.messageRepository = messageRepository;
        this.fileStorageService = fileStorageService;
        this.imageThumbnailService = imageThumbnailService;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
        this.runOnStartup = runOnStartup;
        this.maxPerRun = maxPerRun;
    }

    /**
     * examined = 看过的消息数；created = 新做好预览图的原图数；unavailable = 做不出来的原图数；
     * replaced = 删掉的老预览图文件数（被新的替换、且不再有消息引用）。
     */
    public record Result(int examined, int created, int unavailable, int replaced) {}

    @EventListener(ApplicationReadyEvent.class)
    public void backfillAfterStartup() {
        if (!runOnStartup || maxPerRun <= 0) {
            return;
        }
        taskExecutor.execute(() -> {
            try {
                Result result = backfill(maxPerRun);
                if (result.examined() > 0) {
                    log.info("预览图回填完成: examined={}, created={}, unavailable={}, replaced={}",
                            result.examined(), result.created(), result.unavailable(), result.replaced());
                }
            } catch (Exception e) {
                log.warn("预览图回填失败: {}", e.getMessage());
            }
        });
    }

    /** 最多看 maxMessages 条候选消息，返回处理结果。可以重复调用。 */
    public Result backfill(int maxMessages) {
        int examined = 0;
        int created = 0;
        int unavailable = 0;
        int replaced = 0;
        long afterId = 0L;
        Set<String> handled = new HashSet<>();
        while (examined < maxMessages) {
            List<Message> batch = messageRepository.findThumbnailBackfillCandidates(
                    IMAGE_TYPES,
                    ImageThumbnailService.SMALL_ORIGINAL_BYTES,
                    ImageThumbnailService.RENDITION_VERSION,
                    afterId,
                    PageRequest.of(0, Math.min(BATCH_SIZE, maxMessages - examined)));
            if (batch.isEmpty()) {
                break;
            }
            for (Message message : batch) {
                afterId = message.getId();
                examined++;
                String fileUrl = message.getFileUrl();
                if (!handled.add(fileUrl)) {
                    continue; // 转发副本：上面按 fileUrl 一起换过了
                }
                Optional<ImageThumbnailService.StoredThumbnail> stored = createFor(fileUrl);
                if (stored.isEmpty()) {
                    unavailable++;
                    transactionTemplate.executeWithoutResult(tx -> messageRepository.markRenditionVersionForFileUrl(
                            fileUrl, ImageThumbnailService.RENDITION_VERSION));
                    continue;
                }
                ImageThumbnailService.StoredThumbnail value = stored.get();
                Set<String> previous = new LinkedHashSet<>();
                transactionTemplate.executeWithoutResult(tx -> {
                    previous.addAll(messageRepository.findThumbnailUrlsForFileUrl(fileUrl));
                    previous.addAll(messageRepository.findPreviewUrlsForFileUrl(fileUrl));
                    messageRepository.setRenditionsForFileUrl(
                            fileUrl, value.url(), value.previewUrl(), ImageThumbnailService.RENDITION_VERSION);
                });
                created++;
                replaced += deleteReplaced(previous, value);
            }
        }
        return new Result(examined, created, unavailable, replaced);
    }

    /** 老预览图文件：新地址已经写进所有引用它的消息，确认没有消息再引用才删。 */
    private int deleteReplaced(Set<String> previous, ImageThumbnailService.StoredThumbnail current) {
        int deleted = 0;
        for (String url : previous) {
            if (url.equals(current.url()) || url.equals(current.previewUrl())
                    || storageLocation(url) == null
                    || messageRepository.existsAnyMessageReferencingFileUrl(url)) {
                continue;
            }
            if (fileStorageService.deleteFile(url)) {
                deleted++;
            }
        }
        return deleted;
    }

    private Optional<ImageThumbnailService.StoredThumbnail> createFor(String fileUrl) {
        String[] location = storageLocation(fileUrl);
        if (location == null) {
            return Optional.empty();
        }
        try {
            byte[] original = fileStorageService.getFile(location[0], location[1]);
            return imageThumbnailService.createAndStore(original);
        } catch (Exception e) {
            log.debug("回填预览图时读不到原图 {}: {}", fileUrl, e.getMessage());
            return Optional.empty();
        }
    }
    private static String[] storageLocation(String fileUrl) {
        for (String type : new String[] {"chat", "image-gen"}) {
            String prefix = "/api/files/" + type + "/";
            if (fileUrl.startsWith(prefix)) {
                String name = fileUrl.substring(prefix.length());
                if (!name.isBlank() && !name.contains("/") && !name.contains("..")) {
                    return new String[] {type, name};
                }
            }
        }
        return null;
    }
}

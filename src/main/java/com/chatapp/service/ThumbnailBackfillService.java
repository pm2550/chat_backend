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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 给缩略图上线前发的明文图片消息补缩略图（聊天图片、AI 画图）。
 *
 * 幂等：只处理还没有缩略图的消息，补上以后就不再是候选；同一个原图的转发副本共用一张缩略图。
 * 有上限：做不了缩略图的（损坏、ffmpeg 不可用的 WebP 等）下次启动还会再看一眼，所以每次最多看
 * {@code chat.thumbnails.backfill-max} 条。启动完成后在后台线程跑，不拖慢启动；
 * {@code chat.thumbnails.backfill-on-startup=false} 可关掉。加密附件服务器看不到图，不在范围内。
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
            @Value("${chat.thumbnails.backfill-max:500}") int maxPerRun) {
        this.messageRepository = messageRepository;
        this.fileStorageService = fileStorageService;
        this.imageThumbnailService = imageThumbnailService;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
        this.runOnStartup = runOnStartup;
        this.maxPerRun = maxPerRun;
    }

    public record Result(int examined, int created, int unavailable) {}

    @EventListener(ApplicationReadyEvent.class)
    public void backfillAfterStartup() {
        if (!runOnStartup || maxPerRun <= 0) {
            return;
        }
        taskExecutor.execute(() -> {
            try {
                Result result = backfill(maxPerRun);
                if (result.examined() > 0) {
                    log.info("缩略图回填完成: examined={}, created={}, unavailable={}",
                            result.examined(), result.created(), result.unavailable());
                }
            } catch (Exception e) {
                log.warn("缩略图回填失败: {}", e.getMessage());
            }
        });
    }

    /** 最多看 maxMessages 条候选消息，返回处理结果。可以重复调用。 */
    public Result backfill(int maxMessages) {
        int examined = 0;
        int created = 0;
        int unavailable = 0;
        long afterId = 0L;
        Set<String> handled = new HashSet<>();
        while (examined < maxMessages) {
            List<Message> batch = messageRepository.findThumbnailBackfillCandidates(
                    IMAGE_TYPES,
                    ImageThumbnailService.SMALL_ORIGINAL_BYTES,
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
                    continue; // 转发副本：上面按 fileUrl 一起补过了
                }
                Optional<String> thumbnailUrl = createFor(fileUrl);
                if (thumbnailUrl.isEmpty()) {
                    unavailable++;
                    continue;
                }
                transactionTemplate.executeWithoutResult(tx ->
                        messageRepository.setThumbnailForFileUrl(fileUrl, thumbnailUrl.get()));
                created++;
            }
        }
        return new Result(examined, created, unavailable);
    }

    private Optional<String> createFor(String fileUrl) {
        String[] location = storageLocation(fileUrl);
        if (location == null) {
            return Optional.empty();
        }
        try {
            byte[] original = fileStorageService.getFile(location[0], location[1]);
            return imageThumbnailService.createAndStore(original).map(ImageThumbnailService.StoredThumbnail::url);
        } catch (Exception e) {
            log.debug("回填缩略图时读不到原图 {}: {}", fileUrl, e.getMessage());
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

package com.jarvis.core.pipeline;

import com.jarvis.api.service.TemporaryWorkspaceService;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.TemporaryWorkspaceMetadata;
import com.jarvis.memory.pipeline.PipelineContext;
import com.jarvis.memory.pipeline.PipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Resolves image attachments referenced by the incoming request into base64-encoded
 * {@link ImageAttachment}s, ready for a vision-capable model - runs early so every later stage
 * sees a fully-resolved {@link PipelineContext#images()}.
 *
 * <p>A message with no attachments, or only non-image (text) attachments, produces an empty image
 * list and behaves exactly as before this stage existed.
 */
@Service
@Order(15)
public class ImageAttachmentStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageAttachmentStage.class);

    private final TemporaryWorkspaceService temporaryWorkspaceService;

    /**
     * Creates the image attachment stage.
     *
     * @param temporaryWorkspaceService temporary workspace service
     */
    public ImageAttachmentStage(TemporaryWorkspaceService temporaryWorkspaceService) {
        this.temporaryWorkspaceService = temporaryWorkspaceService;
    }

    @Override
    public String name() {
        return "ImageAttachmentStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        List<AttachmentReference> attachments = context.request().attachments();
        if (attachments.isEmpty()) {
            return context;
        }
        List<ImageAttachment> images = new ArrayList<>();
        for (AttachmentReference reference : attachments) {
            resolveImage(reference).ifPresent(images::add);
        }
        if (images.isEmpty()) {
            return context;
        }
        LOGGER.info("[JARVIS] [IMAGE] Resolved image attachments count={}", images.size());
        return context.withImages(images);
    }

    private java.util.Optional<ImageAttachment> resolveImage(AttachmentReference reference) {
        try {
            TemporaryWorkspaceMetadata workspace = temporaryWorkspaceService.metadata(reference.workspaceId());
            AttachmentMetadata metadata = workspace.attachments().stream()
                    .filter(candidate -> candidate.attachmentId().equals(reference.attachmentId()))
                    .findFirst()
                    .orElse(null);
            if (metadata == null || !temporaryWorkspaceService.isImageExtension(metadata.extension())) {
                return java.util.Optional.empty();
            }
            TemporaryWorkspaceService.ReadableImageAttachment readable = temporaryWorkspaceService.readImageAttachment(reference);
            String base64 = Base64.getEncoder().encodeToString(readable.bytes());
            return java.util.Optional.of(new ImageAttachment(base64, metadata.originalFileName(), reference.attachmentId()));
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] [IMAGE] Failed to resolve image attachment workspace={} attachment={} reason={}",
                    reference.workspaceId(), reference.attachmentId(), exception.getMessage());
            return java.util.Optional.empty();
        }
    }
}

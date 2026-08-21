package com.jarvis.core.pipeline;

import com.jarvis.api.service.TemporaryWorkspaceService;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.TemporaryWorkspaceMetadata;
import com.jarvis.common.image.ConversationImageContext;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageRegistry;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.common.image.ImageSelectionReason;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.core.workspace.TemporaryWorkspaceProperties;
import com.jarvis.memory.image.ConversationImageProperties;
import com.jarvis.memory.image.ConversationImageResolver;
import com.jarvis.memory.pipeline.PipelineContext;
import com.jarvis.memory.pipeline.PipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves image attachments referenced by the incoming request into base64-encoded
 * {@link ImageAttachment}s, ready for a vision-capable model - runs early so every later stage
 * sees a fully-resolved {@link PipelineContext#images()}.
 *
 * <p>A message with no attachments, or only non-image (text) attachments, produces an empty image
 * list and behaves exactly as before this stage existed.
 *
 * <p>Also owns conversation-scoped image memory: current-message images are registered into the
 * durable {@link ConversationImageRegistry}, and the deterministic {@link ConversationImageResolver}
 * decides which earlier-message images (if any) this request's text refers to - those are re-read
 * from the temporary workspace (never assumed to still exist) and merged into the same {@code
 * images()} list, so every later stage (the main model's own vision call, and the native tool loop)
 * automatically re-sees them with no further changes required.
 */
@Service
@Order(15)
public class ImageAttachmentStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageAttachmentStage.class);

    private final TemporaryWorkspaceService temporaryWorkspaceService;
    private final ConversationImageRegistry conversationImageRegistry;
    private final ConversationImageResolver conversationImageResolver;
    private final ConversationImageProperties conversationImageProperties;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the image attachment stage.
     *
     * @param temporaryWorkspaceService temporary workspace service
     * @param conversationImageRegistry durable conversation-scoped image registry
     * @param conversationImageResolver deterministic historical-image reference resolver
     * @param conversationImageProperties conversation image configuration
     * @param temporaryWorkspaceProperties temporary workspace configuration, used only to log a
     *         one-time startup warning when the configured image retention outlives the workspace TTL
     * @param cognitiveEventBus event bus for the {@code CONVERSATION_IMAGES} diagnostic summary
     */
    public ImageAttachmentStage(
            TemporaryWorkspaceService temporaryWorkspaceService,
            ConversationImageRegistry conversationImageRegistry,
            ConversationImageResolver conversationImageResolver,
            ConversationImageProperties conversationImageProperties,
            TemporaryWorkspaceProperties temporaryWorkspaceProperties,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.temporaryWorkspaceService = temporaryWorkspaceService;
        this.conversationImageRegistry = conversationImageRegistry;
        this.conversationImageResolver = conversationImageResolver;
        this.conversationImageProperties = conversationImageProperties;
        this.cognitiveEventBus = cognitiveEventBus;
        if (conversationImageProperties.enabled()
                && temporaryWorkspaceProperties.getTtl().compareTo(conversationImageProperties.retention()) < 0) {
            LOGGER.warn("[CONVERSATION_IMAGES] configuration inconsistency: conversation image retention ({}) is "
                            + "greater than temporary workspace TTL ({}) - images will effectively expire at the "
                            + "shorter workspace TTL instead of the configured retention; a record will still be "
                            + "shown to the model/UI as EXPIRED or MISSING once its physical file is gone, never as AVAILABLE.",
                    conversationImageProperties.retention(), temporaryWorkspaceProperties.getTtl());
        }
    }

    @Override
    public String name() {
        return "ImageAttachmentStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        List<AttachmentReference> attachments = context.request().attachments();
        List<ImageAttachment> currentImages = new ArrayList<>();
        List<AttachmentMetadata> currentMetadata = new ArrayList<>();
        for (AttachmentReference reference : attachments) {
            resolveImage(reference).ifPresent(resolved -> {
                currentImages.add(resolved.image());
                currentMetadata.add(resolved.metadata());
            });
        }
        if (currentImages.isEmpty() && !conversationImageProperties.enabled()) {
            return context;
        }
        Set<String> currentAttachmentIds = new LinkedHashSet<>();
        currentImages.forEach(image -> currentAttachmentIds.add(image.attachmentId()));

        List<ConversationImageRecord> currentRecords = List.of();
        if (conversationImageProperties.enabled() && !currentMetadata.isEmpty()) {
            currentRecords = conversationImageRegistry.registerImages(context.conversationId(), context.requestId(), currentMetadata);
        }

        ConversationImageContext imageContext = ConversationImageContext.empty();
        List<ImageAttachment> finalImages = new ArrayList<>(currentImages);
        if (conversationImageProperties.enabled()) {
            List<ConversationImageRecord> historical = effectiveHistorical(context.conversationId(), currentAttachmentIds);
            ConversationImageContext resolved = conversationImageResolver.resolve(
                    context.request().message(), currentRecords, historical, conversationImageProperties);
            imageContext = verifyAndAttachHistoricalBytes(context, resolved, currentAttachmentIds, finalImages);
        } else if (!currentImages.isEmpty()) {
            imageContext = new ConversationImageContext(currentRecords, List.of(), List.of(), currentRecords, List.of(),
                    ImageSelectionReason.CURRENT_ONLY);
        }

        LOGGER.info("[JARVIS] [IMAGE] Resolved image attachments count={} conversationHistoricalSelected={}",
                currentImages.size(), Math.max(0, imageContext.selectedImagesForModel().size() - currentImages.size()));
        publishDiagnosticSummary(context, imageContext);

        PipelineContext updated = context.withImages(finalImages)
                .withMetadata("currentMessageImageAttachmentIds", List.copyOf(currentAttachmentIds))
                .withMetadata("conversationImageContext", imageContext);
        String promptBlock = buildConversationImagesPromptBlock(imageContext);
        if (!promptBlock.isBlank()) {
            updated = updated.withMetadata("conversationImagesPromptBlock", promptBlock);
        }
        return updated;
    }

    /**
     * Publishes the {@code CONVERSATION_IMAGES} diagnostic summary - counts and identifiers only,
     * never base64/image bytes - and logs the same summary as a single greppable log line. Skipped
     * entirely when this request has nothing to do with images at all (the overwhelming majority of
     * plain text messages), so routine chat traffic never emits a no-op event.
     */
    private void publishDiagnosticSummary(PipelineContext context, ConversationImageContext imageContext) {
        if (!imageContext.hasAnyImages()) {
            return;
        }
        long selectedBytes = imageContext.selectedImagesForModel().stream().mapToLong(ConversationImageRecord::sizeBytes).sum();
        LOGGER.info("[CONVERSATION_IMAGES] conversationId={} current={} historicalAvailable={} historicalExpired={} "
                        + "selected={} selectedBytes={} selectionReason={}",
                context.conversationId(), imageContext.currentMessageImages().size(), imageContext.availableHistoricalImages().size(),
                imageContext.expiredHistoricalImages().size(), imageContext.selectedImagesForModel().size(), selectedBytes,
                imageContext.selectionReason());
        Set<String> selectedIdSet = new LinkedHashSet<>();
        imageContext.selectedImagesForModel().forEach(record -> selectedIdSet.add(record.attachmentId()));
        List<Map<String, Object>> images = new ArrayList<>();
        for (ConversationImageRecord record : imageContext.allKnownImages()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("attachmentId", record.attachmentId());
            entry.put("label", record.conversationLabel());
            entry.put("originalFileName", record.originalFileName());
            entry.put("sourceMessageOrdinal", record.sourceMessageOrdinal());
            entry.put("status", record.status().name());
            entry.put("expiresAt", record.expiresAt() == null ? "" : record.expiresAt().toString());
            entry.put("passedNatively", selectedIdSet.contains(record.attachmentId()));
            images.add(entry);
        }
        cognitiveEventBus.publish(CognitiveEventType.CONVERSATION_IMAGES, imageContext.selectionReason().name(),
                "Conversation image memory summary", null, Map.of(
                        "conversationId", context.conversationId(),
                        "current", imageContext.currentMessageImages().size(),
                        "historicalAvailable", imageContext.availableHistoricalImages().size(),
                        "historicalExpired", imageContext.expiredHistoricalImages().size(),
                        "selected", imageContext.selectedImagesForModel().size(),
                        "selectedBytes", selectedBytes,
                        "selectionReason", imageContext.selectionReason().name(),
                        "images", images
                ));
    }

    /**
     * Reads every known image for this conversation from the registry (excluding the current
     * message's own), correcting any {@code AVAILABLE} record whose retention window has already
     * elapsed to {@code EXPIRED} on the fly - the scheduled sweep may not have run yet, and the
     * resolver must never treat a record as available just because no sweep has caught up with it.
     */
    private List<ConversationImageRecord> effectiveHistorical(String conversationId, Set<String> currentAttachmentIds) {
        Instant now = Instant.now();
        List<ConversationImageRecord> historical = new ArrayList<>();
        for (ConversationImageRecord record : conversationImageRegistry.findForConversation(conversationId)) {
            if (currentAttachmentIds.contains(record.attachmentId())) {
                continue;
            }
            if (record.status() == ConversationImageStatus.AVAILABLE && record.isPastRetention(now)) {
                historical.add(record.withStatus(ConversationImageStatus.EXPIRED));
            } else {
                historical.add(record);
            }
        }
        return historical;
    }

    /**
     * Re-reads the bytes of every historical image the resolver selected, verifying the backing
     * file still actually exists before trusting it - a stored {@code AVAILABLE} status is never
     * itself proof. An image that fails this live check is downgraded to {@code MISSING} in the
     * registry and moved out of the selection into {@code expiredHistoricalImages} instead of ever
     * producing empty/partial image bytes.
     *
     * @param context pipeline context
     * @param resolved the resolver's initial (not yet live-verified) selection
     * @param currentAttachmentIds attachment ids already resolved from the current message (their
     *         bytes are already in {@code finalImages}, never re-read)
     * @param finalImages mutable accumulator to append re-read historical image bytes to
     * @return the final, live-verified conversation image context
     */
    private ConversationImageContext verifyAndAttachHistoricalBytes(
            PipelineContext context,
            ConversationImageContext resolved,
            Set<String> currentAttachmentIds,
            List<ImageAttachment> finalImages
    ) {
        List<ConversationImageRecord> selected = new ArrayList<>();
        List<ConversationImageRecord> downgraded = new ArrayList<>();
        for (ConversationImageRecord record : resolved.selectedImagesForModel()) {
            if (currentAttachmentIds.contains(record.attachmentId())) {
                selected.add(record);
                continue;
            }
            Optional<ImageAttachment> reread = rereadHistoricalImage(record);
            if (reread.isPresent()) {
                finalImages.add(reread.get());
                selected.add(record);
            } else {
                conversationImageRegistry.updateStatus(context.conversationId(), record.attachmentId(), ConversationImageStatus.MISSING);
                downgraded.add(record.withStatus(ConversationImageStatus.MISSING));
                LOGGER.warn("[CONVERSATION_IMAGES] requestId={} conversationId={} attachmentId={} label={} "
                                + "expected AVAILABLE but the backing file is gone - marked MISSING",
                        context.requestId(), context.conversationId(), record.attachmentId(), record.conversationLabel());
            }
        }
        if (downgraded.isEmpty()) {
            return resolved;
        }
        Set<String> downgradedIds = downgraded.stream().map(ConversationImageRecord::attachmentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ConversationImageRecord> availableHistorical = resolved.availableHistoricalImages().stream()
                .filter(record -> !downgradedIds.contains(record.attachmentId())).toList();
        List<ConversationImageRecord> expiredHistorical = new ArrayList<>(resolved.expiredHistoricalImages());
        expiredHistorical.addAll(downgraded);
        return new ConversationImageContext(resolved.currentMessageImages(), availableHistorical, expiredHistorical,
                selected, resolved.skippedDueToLimit(), resolved.selectionReason());
    }

    private Optional<ImageAttachment> rereadHistoricalImage(ConversationImageRecord record) {
        try {
            TemporaryWorkspaceService.ReadableImageAttachment readable = temporaryWorkspaceService.readImageAttachment(
                    new AttachmentReference(record.workspaceId(), record.attachmentId()));
            String base64 = Base64.getEncoder().encodeToString(readable.bytes());
            return Optional.of(new ImageAttachment(base64, record.originalFileName(), record.attachmentId()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Builds the {@code === CONVERSATION IMAGES ===} prompt block the model reads alongside the
     * native vision input itself - textual metadata only, the actual pixels always travel through
     * {@code messages[].images}, never through this text.
     */
    private String buildConversationImagesPromptBlock(ConversationImageContext imageContext) {
        if (!imageContext.hasAnyImages()) {
            return "";
        }
        Set<String> selectedIds = imageContext.selectedImagesForModel().stream()
                .map(ConversationImageRecord::attachmentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        StringBuilder builder = new StringBuilder();
        builder.append("=== CONVERSATION IMAGES ===\n\n");
        boolean hasHistorical = !imageContext.availableHistoricalImages().isEmpty() || !imageContext.expiredHistoricalImages().isEmpty();
        if (hasHistorical) {
            builder.append("The following images were uploaded earlier in this conversation.\n\n");
        }
        for (ConversationImageRecord record : imageContext.availableHistoricalImages()) {
            appendImageEntry(builder, record, selectedIds.contains(record.attachmentId()));
        }
        for (ConversationImageRecord record : imageContext.expiredHistoricalImages()) {
            appendImageEntry(builder, record, false);
        }
        if (!imageContext.skippedDueToLimit().isEmpty()) {
            builder.append("Note: ").append(imageContext.skippedDueToLimit().size())
                    .append(" additional matching historical image(s) were left out to stay within the configured "
                            + "image limit for this request: ");
            builder.append(imageContext.skippedDueToLimit().stream()
                    .map(ConversationImageRecord::conversationLabel)
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            builder.append(".\n\n");
        }
        if (hasHistorical) {
            // "Storage status: AVAILABLE" must never be read as "the model can see the pixels" - a
            // model that conflated the two (Core still holding the temporary file) with (this
            // request's images[] actually contains it) previously reasoned for several minutes about
            // whether it could see an image it was never actually given. "Model can inspect image
            // now" is the one field that answers that question; everything else is provenance only.
            builder.append("An image is visually available to you only when: Model can inspect image now: YES.\n"
                    + "Storage status: AVAILABLE means only that J.A.R.V.I.S. Core still has the temporary file. "
                    + "It does not mean that the image pixels were supplied to this model request.\n"
                    + "Never claim to see, read, describe or analyze an image when: Model can inspect image now: NO.\n"
                    + "Do not reason at length about whether an image might be visible. Trust the explicit "
                    + "Model can inspect image now field.\n"
                    + "If Storage status is AVAILABLE but Model can inspect image now is NO, tell the user which "
                    + "earlier image you would need (its label or file name) and ask them to refer to it more "
                    + "specifically so Core can attach it on the next message - you have no tool to attach it "
                    + "yourself mid-turn.\n"
                    + "Ask the user to upload the image again only when its Storage status is EXPIRED, MISSING, "
                    + "DELETED or INVALID.\n");
        }
        builder.append("\n=== END CONVERSATION IMAGES ===");
        return builder.toString();
    }

    private void appendImageEntry(StringBuilder builder, ConversationImageRecord record, boolean passedNatively) {
        boolean canInspectNow = record.status() == ConversationImageStatus.AVAILABLE && passedNatively;
        builder.append("- ").append(record.conversationLabel()).append('\n')
                .append("  Source message: ").append(record.sourceMessageOrdinal()).append('\n')
                .append("  File name: ").append(record.originalFileName()).append('\n')
                .append("  Storage status: ").append(record.status()).append('\n');
        if (record.status() == ConversationImageStatus.AVAILABLE) {
            builder.append("  Expires at: ").append(record.expiresAt()).append('\n');
        }
        builder.append("  Passed as native visual input in this request: ").append(passedNatively ? "YES" : "NO").append('\n')
                .append("  Model can inspect image now: ").append(canInspectNow ? "YES" : "NO").append('\n');
        if (record.status() != ConversationImageStatus.AVAILABLE) {
            builder.append("  Required action: Ask the user to upload this image again if its contents are needed.\n");
        }
        builder.append('\n');
    }

    private record ResolvedImage(ImageAttachment image, AttachmentMetadata metadata) {
    }

    private Optional<ResolvedImage> resolveImage(AttachmentReference reference) {
        try {
            TemporaryWorkspaceMetadata workspace = temporaryWorkspaceService.metadata(reference.workspaceId());
            AttachmentMetadata metadata = workspace.attachments().stream()
                    .filter(candidate -> candidate.attachmentId().equals(reference.attachmentId()))
                    .findFirst()
                    .orElse(null);
            if (metadata == null || !temporaryWorkspaceService.isImageExtension(metadata.extension())) {
                return Optional.empty();
            }
            TemporaryWorkspaceService.ReadableImageAttachment readable = temporaryWorkspaceService.readImageAttachment(reference);
            String base64 = Base64.getEncoder().encodeToString(readable.bytes());
            return Optional.of(new ResolvedImage(new ImageAttachment(base64, metadata.originalFileName(), reference.attachmentId()), metadata));
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] [IMAGE] Failed to resolve image attachment workspace={} attachment={} reason={}",
                    reference.workspaceId(), reference.attachmentId(), exception.getMessage());
            return Optional.empty();
        }
    }
}

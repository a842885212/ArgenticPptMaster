package domi.argenticpptmaster.service;

import domi.argenticpptmaster.domain.TemplateFillCapabilityIndex;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves and validates template constraints against analyzed slide capabilities. */
@Component
public class TemplateFillConstraintResolver {

    public void validateAgainstLibrary(TemplateFillConstraints constraints, TemplateFillCapabilityIndex index) {
        if (constraints == null || constraints.isEmpty()) {
            return;
        }
        int slideCount = index.slideCount();
        validatePageSet(constraints.allowedTemplateSlides(), slideCount, "allowedTemplateSlides");
        validatePageSet(constraints.excludedTemplateSlides(), slideCount, "excludedTemplateSlides");

        Integer cover = null;
        Integer ending = null;
        for (TemplateFillCapabilityIndex.SlideCapability slide : index.slidesByIndex().values()) {
            if (slide.isCover() && cover == null) {
                cover = slide.slideIndex();
            }
            if (slide.isEnding()) {
                ending = slide.slideIndex();
            }
        }
        if (constraints.preserveCover()) {
            if (cover == null) {
                throw constraintInvalid("preserveCover requires a cover_candidate page in the template");
            }
            rejectBoundaryExclusion(constraints, cover, "cover");
        }
        if (constraints.preserveEnding()) {
            if (ending == null) {
                throw constraintInvalid("preserveEnding requires an ending_candidate page in the template");
            }
            rejectBoundaryExclusion(constraints, ending, "ending");
        }
        if (constraints.maxSlides() != null
                && constraints.requiredBoundaryCount() > constraints.maxSlides()) {
            throw constraintInvalid("maxSlides is smaller than required boundary slides");
        }
    }

    private static void validatePageSet(java.util.List<Integer> pages, int slideCount, String field) {
        for (Integer page : pages) {
            if (page > slideCount) {
                throw constraintInvalid(field + " references page outside template: " + page);
            }
        }
    }

    private static void rejectBoundaryExclusion(
            TemplateFillConstraints constraints, int page, String label) {
        if (constraints.excludedTemplateSlides().contains(page)) {
            throw constraintInvalid("cannot exclude required " + label + " page " + page);
        }
        if (!constraints.allowedTemplateSlides().isEmpty()
                && !constraints.allowedTemplateSlides().contains(page)) {
            throw constraintInvalid("required " + label + " page " + page + " is not in allowedTemplateSlides");
        }
    }

    private static PptJobStateException constraintInvalid(String message) {
        return new PptJobStateException(message + " [" + TemplateFillErrorCode.TEMPLATE_CONSTRAINT_INVALID.code() + "]");
    }
}

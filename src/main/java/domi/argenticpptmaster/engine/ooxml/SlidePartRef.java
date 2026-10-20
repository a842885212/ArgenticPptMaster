// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml;

/** A presentation slide part resolved from presentation.xml + relationships. */
public record SlidePartRef(int index, String partName, int slideNumber) {
}

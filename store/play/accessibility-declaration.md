# AccessibilityService declaration

No AccessibilityService declaration or review video is required for the Play artifact. Its merged
manifest must not contain `BIND_ACCESSIBILITY_SERVICE` or `InteractionCollectionService`, and its
distribution policy rejects the Interaction Events module before consent. Accessibility collection
remains research-build-only and must not be described or enabled in the Play reviewer study.

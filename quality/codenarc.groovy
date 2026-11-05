/** Checks build callbacks individually as well as ordinary methods and declarations. */
ruleset {
    CyclomaticComplexity {
        maxMethodComplexity = 10
        maxClassAverageMethodComplexity = 10
    }
    rule(raknetify.quality.ClosureComplexityRule)
    rule(raknetify.quality.DocumentationRule)
    ClassJavadoc { applyToNonMainClasses = true }
}

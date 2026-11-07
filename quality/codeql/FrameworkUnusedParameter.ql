/**
 * @name Unused parameter outside a required callback signature
 * @description Parameters that are neither read nor required by an external callback contract add unnecessary complexity.
 * @kind problem
 * @problem.severity recommendation
 * @precision high
 * @id raknetify2/java/unused-parameter
 * @tags quality
 *       maintainability
 *       useless-code
 *       external/cwe/cwe-561
 */

import semmle.code.java.deadcode.DeadCode

/** Recognizes Mixin-generated calls only inside a class explicitly transformed as a mixin. */
private predicate hasMixinSignature(Callable callable) {
  callable.getDeclaringType().getAnAnnotation().getType()
      .hasQualifiedName("org.spongepowered.asm.mixin", "Mixin") and
  exists(AnnotationType annotation | annotation = callable.getAnAnnotation().getType() |
    annotation.hasQualifiedName("org.spongepowered.asm.mixin.injection",
      ["Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant", "Surrogate"])
    or
    annotation.hasQualifiedName("com.llamalad7.mixinextras.injector",
      ["ModifyExpressionValue", "ModifyReturnValue", "WrapWithCondition"])
    or
    annotation.hasQualifiedName("com.llamalad7.mixinextras.injector.v2", "WrapWithCondition")
    or
    annotation.hasQualifiedName("com.llamalad7.mixinextras.injector.wrapoperation", "WrapOperation")
    or
    annotation.hasQualifiedName("com.llamalad7.mixinextras.injector.wrapmethod", "WrapMethod")
  )
}

/** Velocity supplies one event argument to subscribed void methods through its event registry. */
private predicate hasVelocitySignature(Callable callable) {
  callable.getAnAnnotation().getType().hasQualifiedName("com.velocitypowered.api.event", "Subscribe") and
  callable.getNumberOfParameters() = 1 and
  callable.(Method).getReturnType() instanceof VoidType
}

/** Default interface callbacks preserve an extension contract even when the default body ignores it. */
private predicate hasDefaultCallbackSignature(Callable callable) {
  callable.(Method).getDeclaringType() instanceof Interface and
  callable.hasModifier("default")
}

from RootdefCallable callable
where
  not callable.whitelisted() and
  not hasMixinSignature(callable) and
  not hasVelocitySignature(callable) and
  not hasDefaultCallbackSignature(callable)
select callable.unusedParameter() as parameter, "The parameter '" + parameter + "' is never used."

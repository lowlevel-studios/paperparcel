package nz.bradcampbell.kraftpaper.internal.properties;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.ParameterSpec;
import nz.bradcampbell.kraftpaper.internal.Property;
import org.jetbrains.annotations.Nullable;

import static nz.bradcampbell.kraftpaper.internal.Utils.literal;

public class BundleProperty extends Property {
  public BundleProperty(Property.Type propertyType, boolean isNullable, String name) {
    super(propertyType, isNullable, name);
  }

  @Override protected CodeBlock readFromParcelInner(CodeBlock.Builder block, ParameterSpec in, @Nullable FieldSpec classLoader) {
    return literal("$N.readBundle($N)", in, classLoader);
  }

  @Override protected void writeToParcelInner(CodeBlock.Builder block, ParameterSpec dest, CodeBlock sourceLiteral) {
    block.addStatement("$N.writeBundle($L)", dest, sourceLiteral);
  }
}
package nz.bradcampbell.dataparcel;

import android.os.Parcelable;
import com.google.auto.service.AutoService;
import com.google.common.base.Joiner;
import com.squareup.javapoet.*;
import nz.bradcampbell.dataparcel.internal.DataClass;
import nz.bradcampbell.dataparcel.internal.Property;
import nz.bradcampbell.dataparcel.internal.PropertyCreator;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

import static javax.lang.model.element.Modifier.*;
import static nz.bradcampbell.dataparcel.internal.PropertyCreator.createProperty;

@AutoService(Processor.class)
public class DataParcelProcessor extends AbstractProcessor {
  private static final String NULLABLE_ANNOTATION_NAME = "Nullable";

  public static final String DATA_VARIABLE_NAME = "data";

  private Elements elementUtils;
  private Filer filer;
  private Types typeUtil;
  private Map<String, DataClass> parcels = new HashMap<String, DataClass>();

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);
    elementUtils = env.getElementUtils();
    filer = env.getFiler();
    typeUtil = env.getTypeUtils();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton(DataParcel.class.getCanonicalName());
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    if (annotations.isEmpty()) {

      // Nothing to do
      return true;
    }

    // Create a DataClass models for all classes annotated with @DataParcel
    for (Element element : roundEnvironment.getElementsAnnotatedWith(DataParcel.class)) {

      // Ensure we are dealing with a TypeElement
      if (!(element instanceof TypeElement)) {
        error("@DataParcel applies to a type, " + element.getSimpleName() + " is a " + element.getKind(), element);
        continue;
      }

      TypeElement el = (TypeElement) element;
      createParcel(el);
    }

    // Generate java files for every data class found
    for (DataClass p : parcels.values()) {
      try {
        generateJavaFileFor(p).writeTo(filer);
      } catch (IOException e) {
        throw new RuntimeException("An error occurred while writing to filer.", e);
      }
    }

    return true;
  }

  /**
   * Create a Parcel wrapper for the given data class
   *
   * @param typeElement The data class
   */
  private void createParcel(TypeElement typeElement) {

    // Exit early if we have already created a parcel for this data class
    String className = typeElement.getQualifiedName().toString();
    if (parcels.containsKey(className)) return;

    // Needs to be in the same package as the data class
    String classPackage = getPackageName(typeElement);

    // Name is always {className}Parcel
    String wrappedClassName = ClassName.get(typeElement).simpleName() + "Parcel";

    List<Property> properties = new ArrayList<Property>();
    List<TypeElement> variableDataParcelDependencies = new ArrayList<TypeElement>();

    // Get all member variable elements in the data class
    List<VariableElement> variableElements = getFields(typeElement);

    for (int i = 0; i < variableElements.size(); i++) {
      VariableElement variableElement = variableElements.get(i);

      // A field is only "nullable" when annotated with @Nullable
      boolean isNullable = !isFieldRequired(variableElement);

      // Parse the property type into a Property.Type object and find all recursive data class dependencies
      Property.Type propertyType = parsePropertyType(variableElement.asType(), variableDataParcelDependencies);

      // Validate data class has a method for retrieving the member variable
      String getterMethodName = "component" + (i + 1);
      if (!canFindGetterMethodForProperty(typeElement, propertyType, getterMethodName)) {
        error(typeElement.getSimpleName() + " is not a supported type.", typeElement);
      }

      properties.add(createProperty(propertyType, isNullable, getterMethodName));
    }

    parcels.put(className, new DataClass(properties, classPackage, wrappedClassName, typeElement));

    // Build parcel dependencies
    for (TypeElement requiredParcel : variableDataParcelDependencies) {
      createParcel(requiredParcel);
    }
  }

  /**
   * Ensures the data class has a getter method that we can use to access the property
   *
   * @param typeElement The data class
   * @param propertyType A property in the data class
   * @param getterMethodName The expected name for the property getter method
   * @return true if a getter method is found, false otherwise
   */
  private boolean canFindGetterMethodForProperty(TypeElement typeElement, Property.Type propertyType, String getterMethodName) {
    for (Element enclosedElement : typeElement.getEnclosedElements()) {

      // Find all enclosing methods
      if (enclosedElement instanceof ExecutableElement) {
        ExecutableElement method = (ExecutableElement) enclosedElement;

        // Check this method returns the property type
        TypeName returnType = TypeName.get(method.getReturnType());
        if (returnType.equals(propertyType.getTypeName())) {

          // Check the method name matches what we're expecting
          if (method.getSimpleName().toString().equals(getterMethodName)) {

            // Property is valid
            return true;
          }
        }
      }
    }

    // Could not find an appropriate getter method for the property
    return false;
  }

  private Property.Type parsePropertyType(TypeMirror type, List<TypeElement> variableDataParcelDependencies) {
    TypeMirror erasedType = typeUtil.erasure(type);

    // List of type arguments for this property
    List<Property.Type> childTypes = null;

    // The type that allows this type to be parcelable, or null
    TypeName parcelableTypeName = PropertyCreator.getParcelableType(typeUtil, erasedType);

    TypeName typeName = ClassName.get(erasedType);
    TypeName wrappedTypeName = typeName;

    boolean isParcelable = parcelableTypeName != null;

    Element typeElement = typeUtil.asElement(erasedType);

    if (isParcelable) {

      if (type instanceof DeclaredType) {

        // Parse type arguments
        DeclaredType declaredType = (DeclaredType) type;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();

        int numTypeArgs = typeArguments.size();
        if (numTypeArgs > 0) {

          childTypes = new ArrayList<Property.Type>(numTypeArgs);
          TypeName[] parameterArray = new TypeName[numTypeArgs];
          TypeName[] wrappedParameterArray = new TypeName[numTypeArgs];

          for (int i = 0; i < numTypeArgs; i++) {
            Property.Type argType = parsePropertyType(typeArguments.get(i), variableDataParcelDependencies);
            childTypes.add(argType);
            parameterArray[i] = argType.getTypeName();
            wrappedParameterArray[i] = argType.getWrappedTypeName();
          }

          wrappedTypeName = ParameterizedTypeName.get((ClassName) typeName, wrappedParameterArray);
          typeName = ParameterizedTypeName.get((ClassName) typeName, parameterArray);
        }
      }

      if (erasedType instanceof ArrayType) {
        ArrayType arrayType = (ArrayType) erasedType;

        childTypes = new ArrayList<Property.Type>(1);

        Property.Type componentType = parsePropertyType(arrayType.getComponentType(), variableDataParcelDependencies);
        childTypes.add(componentType);

        wrappedTypeName = ArrayTypeName.of(componentType.getWrappedTypeName());
        typeName = ArrayTypeName.of(componentType.getTypeName());
      }

    } else {

      // This is (one of) the reason(s) it is not parcelable. Assume it contains a data object as a parameter
      TypeElement requiredElement = (TypeElement) typeElement;
      variableDataParcelDependencies.add(requiredElement);
      String packageName = getPackageName(requiredElement);
      String className = requiredElement.getSimpleName().toString() + "Parcel";
      parcelableTypeName = wrappedTypeName = ClassName.get(packageName, className);
    }

    boolean isInterface = typeElement != null && typeElement.getKind() == ElementKind.INTERFACE;

    return new Property.Type(childTypes, parcelableTypeName, typeName, wrappedTypeName, typeName.equals(wrappedTypeName), isInterface);
  }

  /**
   * Gets a list of all non-static member variables of a TypeElement
   *
   * @param el The data class
   * @return A list of non-static member variables. Cannot be null.
   */
  private List<VariableElement> getFields(TypeElement el) {
    List<? extends Element> enclosedElements = el.getEnclosedElements();
    List<VariableElement> variables = new ArrayList<VariableElement>();
    for (Element e : enclosedElements) {
      if (e instanceof VariableElement && !e.getModifiers().contains(STATIC)) {
        variables.add((VariableElement) e);
      }
    }
    return variables;
  }

  private void error(String message, Element element) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }

  private String getPackageName(TypeElement type) {
    return elementUtils.getPackageOf(type).getQualifiedName().toString();
  }

  public static boolean hasAnnotationWithName(Element element, String simpleName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      String annotationName = mirror.getAnnotationType().asElement().getSimpleName().toString();
      if (simpleName.equals(annotationName)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isFieldRequired(Element element) {
    return !hasAnnotationWithName(element, NULLABLE_ANNOTATION_NAME);
  }

  private JavaFile generateJavaFileFor(DataClass dataClass) {
    TypeSpec.Builder o = TypeSpec.classBuilder(dataClass.getWrapperClassName().simpleName())
        .addModifiers(PUBLIC)
        .addSuperinterface(Parcelable.class)
        .addField(generateCreator(dataClass))
        .addField(generateContentsField(dataClass))
        .addMethod(generateWrapMethod(dataClass))
        .addMethod(generateContentsConstructor(dataClass))
        .addMethod(generateParcelConstructor(dataClass))
        .addMethod(generateGetter(dataClass))
        .addMethod(generateDescribeContents())
        .addMethod(generateWriteToParcel(dataClass));
    return JavaFile.builder(dataClass.getClassPackage(), o.build()).build();
  }

  private FieldSpec generateCreator(DataClass dataClass) {
    ClassName className = dataClass.getWrapperClassName();
    ClassName creator = ClassName.get("android.os", "Parcelable", "Creator");
    TypeName creatorOfClass = ParameterizedTypeName.get(creator, className);
    return FieldSpec
        .builder(creatorOfClass, "CREATOR", Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC)
        .initializer(CodeBlock.builder()
            .beginControlFlow("new $T()", ParameterizedTypeName.get(creator, className))
            .beginControlFlow("@$T public $T createFromParcel($T in)", ClassName.get(Override.class), className,
                ClassName.get(android.os.Parcel.class))
            .addStatement("return new $T(in)", className)
            .endControlFlow()
            .beginControlFlow("@$T public $T[] newArray($T size)", ClassName.get(Override.class), className, int.class)
            .addStatement("return new $T[size]", className)
            .endControlFlow()
            .unindent()
            .add("}")
            .build())
        .build();
  }

  private FieldSpec generateContentsField(DataClass dataClass) {
    return FieldSpec.builder(dataClass.getDataClassTypeName(), DATA_VARIABLE_NAME, PRIVATE, FINAL).build();
  }

  private MethodSpec generateWrapMethod(DataClass dataClass) {
    ClassName className = dataClass.getWrapperClassName();
    return MethodSpec.methodBuilder("wrap")
        .addModifiers(PUBLIC, STATIC, FINAL)
        .addParameter(dataClass.getDataClassTypeName(), DATA_VARIABLE_NAME)
        .addStatement("return new $T($N)", className, DATA_VARIABLE_NAME)
        .returns(className)
        .build();
  }

  private MethodSpec generateContentsConstructor(DataClass dataClass) {
    return MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(dataClass.getDataClassTypeName(), DATA_VARIABLE_NAME)
        .addStatement("this.$N = $N", DATA_VARIABLE_NAME, DATA_VARIABLE_NAME)
        .build();
  }

  private MethodSpec generateParcelConstructor(DataClass dataClass) {
    ParameterSpec in = ParameterSpec
        .builder(ClassName.get("android.os", "Parcel"), "in")
        .build();
    MethodSpec.Builder builder = MethodSpec.constructorBuilder()
        .addModifiers(PRIVATE)
        .addParameter(in);
    List<String> paramNames = new ArrayList<String>();
    for (Property p : dataClass.getDataClassProperties()) {
      builder.addCode(p.readFromParcel(in));
      paramNames.add(p.getName());
    }
    builder.addStatement("this.$N = new $T($N)", DATA_VARIABLE_NAME, dataClass.getDataClassTypeName(),
        Joiner.on(", ").join(paramNames));
    return builder.build();
  }

  private MethodSpec generateGetter(DataClass dataClass) {
    return MethodSpec.methodBuilder("getContents")
        .addModifiers(PUBLIC)
        .returns(dataClass.getDataClassTypeName())
        .addStatement("return $N", DATA_VARIABLE_NAME)
        .build();
  }

  private MethodSpec generateDescribeContents() {
    return MethodSpec.methodBuilder("describeContents")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .returns(int.class)
        .addStatement("return 0")
        .build();
  }

  private MethodSpec generateWriteToParcel(DataClass dataClass) {
    ParameterSpec dest = ParameterSpec
        .builder(ClassName.get("android.os", "Parcel"), "dest")
        .build();
    MethodSpec.Builder builder = MethodSpec.methodBuilder("writeToParcel")
        .addAnnotation(Override.class)
        .addModifiers(PUBLIC)
        .addParameter(dest)
        .addParameter(int.class, "flags");
    for (Property p : dataClass.getDataClassProperties()) {
      builder.addCode(p.writeToParcel(dest));
    }
    return builder.build();
  }
}

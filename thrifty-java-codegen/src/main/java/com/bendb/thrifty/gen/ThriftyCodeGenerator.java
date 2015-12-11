package com.bendb.thrifty.gen;

import com.bendb.thrifty.ThriftField;
import com.bendb.thrifty.schema.Constant;
import com.bendb.thrifty.schema.EnumType;
import com.bendb.thrifty.schema.Field;
import com.bendb.thrifty.schema.Location;
import com.bendb.thrifty.schema.Named;
import com.bendb.thrifty.schema.NamespaceScope;
import com.bendb.thrifty.schema.Schema;
import com.bendb.thrifty.schema.StructType;
import com.bendb.thrifty.schema.ThriftType;
import com.bendb.thrifty.schema.parser.ConstValueElement;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import javax.annotation.Generated;
import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class ThriftyCodeGenerator {
    private static final String FILE_COMMENT = "Automatically generated by the Thrifty compiler; do not edit!";

    public static final String ADAPTER_FIELDNAME = "ADAPTER";

    private static final DateTimeFormatter DATE_FORMATTER =
            ISODateTimeFormat.dateTime().withZoneUTC();

    private final TypeResolver typeResolver = new TypeResolver();
    private final Schema schema;

    public ThriftyCodeGenerator(Schema schema) {
        this(
                schema,
                ClassName.get(ArrayList.class),
                ClassName.get(HashSet.class),
                ClassName.get(HashMap.class));
    }

    private ThriftyCodeGenerator(
            Schema schema,
            ClassName listClassName,
            ClassName setClassName,
            ClassName mapClassName) {

        Preconditions.checkNotNull(schema, "schema");
        Preconditions.checkNotNull(listClassName, "listClassName");
        Preconditions.checkNotNull(setClassName, "setClassName");
        Preconditions.checkNotNull(mapClassName, "mapClassName");

        this.schema = schema;
        typeResolver.setListClass(listClassName);
        typeResolver.setSetClass(setClassName);
        typeResolver.setMapClass(mapClassName);
    }

    public ThriftyCodeGenerator withListType(String listClassName) {
        typeResolver.setListClass(ClassName.bestGuess(listClassName));
        return this;
    }

    public ThriftyCodeGenerator withSetType(String setClassName) {
        typeResolver.setSetClass(ClassName.bestGuess(setClassName));
        return this;
    }

    public ThriftyCodeGenerator withMapType(String mapClassName) {
        typeResolver.setMapClass(ClassName.bestGuess(mapClassName));
        return this;
    }

    public void generate(final File directory) throws IOException {
        generate(new FileWriter() {
            @Override
            public void write(JavaFile file) throws IOException {
                file.writeTo(directory);
            }
        });
    }

    public void generate(final Appendable appendable) throws IOException {
        generate(new FileWriter() {
            @Override
            public void write(JavaFile file) throws IOException {
                file.writeTo(appendable);
            }
        });
    }

    private interface FileWriter {
        void write(JavaFile file) throws IOException;
    }

    private void generate(FileWriter writer) throws IOException {
        for (EnumType type : schema.enums()) {
            TypeSpec spec = buildEnum(type);
            JavaFile file = assembleJavaFile(type, spec);
            writer.write(file);
        }

        for (StructType struct : schema.structs()) {
            TypeSpec spec = buildStruct(struct);
            JavaFile file = assembleJavaFile(struct, spec);
            writer.write(file);
        }

        for (StructType exception : schema.exceptions()) {
            TypeSpec spec = buildStruct(exception);
            JavaFile file = assembleJavaFile(exception, spec);
            writer.write(file);
        }

        for (StructType union : schema.unions()) {
            TypeSpec spec = buildStruct(union);
            JavaFile file = assembleJavaFile(union, spec);
            writer.write(file);
        }

        Multimap<String, Constant> constantsByPackage = HashMultimap.create();
        for (Constant constant : schema.constants()) {
            constantsByPackage.put(constant.getNamespaceFor(NamespaceScope.JAVA), constant);
        }

        for (Map.Entry<String, Collection<Constant>> entry : constantsByPackage.asMap().entrySet()) {
            String packageName = entry.getKey();
            Collection<Constant> values = entry.getValue();
            TypeSpec spec = buildConst(values);
            JavaFile file = assembleJavaFile(packageName, spec);
            writer.write(file);
        }

        // TODO: Services
    }

    private JavaFile assembleJavaFile(Named named, TypeSpec spec) {
        String packageName = named.getNamespaceFor(NamespaceScope.JAVA);
        if (Strings.isNullOrEmpty(packageName)) {
            throw new IllegalArgumentException("A Java package name must be given for java code generation");
        }

        return assembleJavaFile(packageName, spec, named.location());
    }

    private JavaFile assembleJavaFile(String packageName, TypeSpec spec) {
        return assembleJavaFile(packageName, spec, null);
    }

    private JavaFile assembleJavaFile(String packageName, TypeSpec spec, Location location) {
        JavaFile.Builder file = JavaFile.builder(packageName, spec)
                .skipJavaLangImports(true)
                .addFileComment(FILE_COMMENT);

        if (location != null) {
            file.addFileComment("\nSource: $L", location);
        }

        return file.build();
    }

    TypeSpec buildStruct(StructType type) {
        String packageName = type.getNamespaceFor(NamespaceScope.JAVA);
        ClassName structTypeName = ClassName.get(packageName, type.name());
        ClassName builderTypeName = structTypeName.nestedClass("Builder");

        TypeSpec.Builder structBuilder = TypeSpec.classBuilder(type.name())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        if (type.hasJavadoc()) {
            structBuilder.addJavadoc(type.documentation());
        }

        if (type.isException()) {
            structBuilder.superclass(Exception.class);
        }

        TypeSpec builderSpec = builderFor(type, structTypeName, builderTypeName);
        TypeSpec adapterSpec = adapterFor(type, structTypeName, builderTypeName);

        structBuilder.addType(builderSpec);
        structBuilder.addType(adapterSpec);
        structBuilder.addField(FieldSpec.builder(adapterSpec.superinterfaces.get(0), ADAPTER_FIELDNAME)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $N()", adapterSpec)
                .build());

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(builderTypeName, "builder");

        for (Field field : type.fields()) {
            String name = field.name();
            ThriftType fieldType = field.type();
            ThriftType trueType = fieldType.getTrueType();
            TypeName fieldTypeName = typeResolver.getJavaClass(trueType);

            // Define field
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(fieldTypeName, name)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addAnnotation(fieldAnnotation(field));

            if (field.hasJavadoc()) {
                fieldBuilder = fieldBuilder.addJavadoc(field.documentation());
            }

            structBuilder.addField(fieldBuilder.build());

            // Update the struct ctor

            CodeBlock.Builder assignment = CodeBlock.builder().add("$[this.$N = ", name);

            if (trueType.isList()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableList(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isSet()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableSet(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isMap()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableMap(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else {
                assignment.add("builder.$N", name);
            }

            ctor.addCode(assignment.add(";\n$]").build());
        }

        structBuilder.addMethod(ctor.build());
        structBuilder.addMethod(buildEqualsFor(type));
        structBuilder.addMethod(buildHashCodeFor(type));
        structBuilder.addMethod(buildToStringFor(type));

        return structBuilder.build();
    }

    private TypeSpec builderFor(
            StructType structType,
            ClassName structClassName,
            ClassName builderClassName) {
        TypeName builderSuperclassName = ParameterizedTypeName.get(TypeNames.BUILDER, structClassName);
        TypeSpec.Builder builder = TypeSpec.classBuilder("Builder")
                .addSuperinterface(builderSuperclassName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        MethodSpec.Builder buildMethodBuilder = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .returns(structClassName)
                .addModifiers(Modifier.PUBLIC);

        MethodSpec.Builder resetBuilder = MethodSpec.methodBuilder("reset")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC);

        MethodSpec.Builder copyCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(structClassName, "struct");

        MethodSpec.Builder defaultCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        if (structType.isUnion()) {
            buildMethodBuilder.addStatement("int setFields = 0");
        }

        // Add fields to the struct and set them in the ctor
        NameAllocator allocator = new NameAllocator();
        for (Field field : structType.fields()) {
            allocator.newName(field.name(), field.name());
        }

        for (Field field : structType.fields()) {
            ThriftType fieldType = field.type().getTrueType();
            TypeName javaTypeName = typeResolver.getJavaClass(fieldType);
            String fieldName = field.name();
            FieldSpec.Builder f = FieldSpec.builder(javaTypeName, fieldName, Modifier.PRIVATE);

            if (field.hasJavadoc()) {
                f.addJavadoc(field.documentation());
            }

            if (field.defaultValue() != null) {
                CodeBlock.Builder initializer = CodeBlock.builder();
                generateFieldInitializer(
                        initializer,
                        allocator,
                        "this." + field.name(),
                        fieldType.getTrueType(),
                        field.defaultValue());
                defaultCtor.addCode(initializer.build());

                resetBuilder.addCode(initializer.build());
            } else {
                resetBuilder.addStatement("this.$N = null", fieldName);
            }

            builder.addField(f.build());

            MethodSpec.Builder setterBuilder = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderClassName)
                    .addParameter(javaTypeName, fieldName);

            if (field.required()) {
                setterBuilder.beginControlFlow("if ($N == null)", fieldName);
                setterBuilder.addStatement(
                        "throw new $T(\"Required field '$L' cannot be null\")",
                        NullPointerException.class,
                        fieldName);
                setterBuilder.endControlFlow();
            }

            setterBuilder
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this");

            builder.addMethod(setterBuilder.build());

            if (structType.isUnion()) {
                buildMethodBuilder
                        .addStatement("if (this.$N != null) ++setFields", fieldName);
            } else {
                if (field.required()) {
                    buildMethodBuilder.beginControlFlow("if (this.$N == null)", fieldName);
                    buildMethodBuilder.addStatement(
                            "throw new $T($S)",
                            ClassName.get(IllegalStateException.class),
                            "Required field '" + fieldName + "' is missing");
                    buildMethodBuilder.endControlFlow();
                }
            }

            copyCtor.addStatement("this.$N = $N.$N", fieldName, "struct", fieldName);
        }

        if (structType.isUnion()) {
            buildMethodBuilder
                    .beginControlFlow("if (setFields != 1)")
                    .addStatement(
                            "throw new $T($S + setFields + $S)",
                            ClassName.get(IllegalStateException.class),
                            "Invalid union; ",
                            " field(s) were set")
                    .endControlFlow();
        }

        buildMethodBuilder.addStatement("return new $T(this)", structClassName);
        builder.addMethod(defaultCtor.build());
        builder.addMethod(copyCtor.build());
        builder.addMethod(buildMethodBuilder.build());
        builder.addMethod(resetBuilder.build());

        return builder.build();
    }

    private TypeSpec adapterFor(StructType structType, ClassName structClassName, ClassName builderClassName) {
        TypeName adapterSuperclass = ParameterizedTypeName.get(
                TypeNames.ADAPTER,
                structClassName,
                builderClassName);

        final MethodSpec.Builder write = MethodSpec.methodBuilder("write")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(structClassName, "struct")
                .addException(IOException.class);

        final MethodSpec.Builder read = MethodSpec.methodBuilder("read")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(typeResolver.getJavaClass(structType.type()))
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(builderClassName, "builder")
                .addException(IOException.class);

        final MethodSpec readHelper = MethodSpec.methodBuilder("read")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(typeResolver.getJavaClass(structType.type()))
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addException(IOException.class)
                .addStatement("return read(protocol, new $T())", builderClassName)
                .build();

        // First, the writer
        write.addStatement("protocol.writeStructBegin($S)", structType.name());

        // Then, the reader - set up the field-reading loop.
        read.addStatement("protocol.readStructBegin()");
        read.beginControlFlow("while (true)");
        read.addStatement("$T field = protocol.readFieldBegin()", TypeNames.FIELD_METADATA);
        read.beginControlFlow("if (field.typeId == $T.STOP)", TypeNames.TTYPE);
        read.addStatement("break");
        read.endControlFlow();

        if (structType.fields().size() > 0) {
            read.beginControlFlow("switch (field.fieldId)");
        }

        for (Field field : structType.fields()) {
            boolean optional = !field.required();
            final String name = field.name();
            final ThriftType tt = field.type().getTrueType();
            byte typeCode = typeResolver.getTypeCode(tt);
            String typeCodeName = TypeNames.getTypeCodeName(typeCode);

            // Write
            if (optional) {
                write.beginControlFlow("if (struct.$N != null)", name);
            }

            write.addStatement(
                    "protocol.writeFieldBegin($S, $L, $T.$L)",
                    name,
                    field.id(),
                    TypeNames.TTYPE,
                    typeCodeName);

            tt.accept(new GenerateWriterVisitor(typeResolver, write, "protocol", "struct", field));

            write.addStatement("protocol.writeFieldEnd()");

            if (optional) {
                write.endControlFlow();
            }

            // Read
            read.beginControlFlow("case $L:", field.id());
            new GenerateReaderVisitor(typeResolver, read, field).generate();
            read.endControlFlow(); // end case block
            read.addStatement("break");
        }

        write.addStatement("protocol.writeFieldStop()");
        write.addStatement("protocol.writeStructEnd()");

        if (structType.fields().size() > 0) {
            read.beginControlFlow("default:");
            read.addStatement("$T.skip(protocol, field.typeId)", TypeNames.PROTO_UTIL);
            read.endControlFlow(); // end default
            read.addStatement("break");
            read.endControlFlow(); // end switch
        }

        read.addStatement("protocol.readFieldEnd()");
        read.endControlFlow(); // end while
        read.addStatement("return builder.build()");

        return TypeSpec.classBuilder(structType.name() + "Adapter")
                .addSuperinterface(adapterSuperclass)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addMethod(write.build())
                .addMethod(read.build())
                .addMethod(readHelper)
                .build();
    }

    private MethodSpec buildEqualsFor(StructType struct) {
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "other")
                .addStatement("if (this == other) return true")
                .addStatement("if (other == null) return false")
                .addStatement("if (!(other instanceof $L)) return false", struct.name());


        if (struct.fields().size() > 0) {
            equals.addStatement("$1L that = ($1L) other", struct.name());
        }

        boolean isFirst = true;
        for (Field field : struct.fields()) {
            if (isFirst) {
                equals.addCode("$[return (this.$1N == that.$1N || (this.$1N != null && this.$1N.equals(that.$1N)))",
                        field.name());
                isFirst = false;
            } else {
                equals.addCode("\n&& (this.$1N == that.$1N || (this.$1N != null && this.$1N.equals(that.$1N)))",
                        field.name());
            }
        }

        if (struct.fields().size() > 0) {
            equals.addCode(";\n$]");
        } else {
            equals.addStatement("return true");
        }

        return equals.build();
    }

    private MethodSpec buildHashCodeFor(StructType struct) {
        MethodSpec.Builder hashCode = MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("int code = 16777619");

        for (Field field : struct.fields()) {
            hashCode.addStatement("code ^= (this.$1N == null) ? 0 : this.$1N.hashCode()", field.name());
            hashCode.addStatement("code *= 0x811c9dc5");
        }

        hashCode.addStatement("return code");
        return hashCode.build();
    }

    private MethodSpec buildToStringFor(StructType struct) {
        MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class);

        if (struct.fields().size() > 0) {
            toString.addStatement("$1T sb = new $1T()", TypeNames.STRING_BUILDER);
            toString.addStatement("sb.append($S).append(\"{\\n  \")", struct.name());

            int index = 0;
            for (Field field : struct.fields()) {
                boolean isLast = ++index == struct.fields().size();
                toString.addStatement("sb.append($S)", field.name() + "=");
                toString.addStatement("sb.append(this.$1N == null ? \"null\" : this.$1N)", field.name());
                if (isLast) {
                    toString.addStatement("sb.append(\"\\n}\")");
                } else {
                    toString.addStatement("sb.append(\",\\n  \")");
                }
            }

            toString.addStatement("return sb.toString()");
        } else {
            toString.addStatement("return $S", struct.name() + "{}");
        }

        return toString.build();
    }

    TypeSpec buildConst(Collection<Constant> constants) {
        TypeSpec.Builder builder = TypeSpec.classBuilder("Constants")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addCode("// no instances\n")
                        .build());

        NameAllocator allocator = new NameAllocator();
        allocator.newName("Constants", "Constants");

        List<Constant> needsStaticInit = new ArrayList<>();
        for (Constant constant : constants) {
            ThriftType type = constant.type().getTrueType();

            TypeName javaType = typeResolver.getJavaClass(type);
            if (type.isBuiltin() && type != ThriftType.STRING) {
                javaType = javaType.unbox();
            }
            FieldSpec.Builder field = FieldSpec.builder(javaType, constant.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

            if (constant.hasJavadoc()) {
                field.addJavadoc(constant.documentation());
            }

            boolean isCollection = type.isList() || type.isMap() || type.isSet();
            if (!isCollection) {
                CodeBlock fieldInit = renderConstValue(null, allocator, type, constant.value());
                field.initializer(fieldInit);
            } else {
                needsStaticInit.add(constant);
            }

            builder.addField(field.build());
        }

        if (needsStaticInit.size() > 0) {
            CodeBlock.Builder init = CodeBlock.builder();

            for (Constant constant : needsStaticInit) {
                ThriftType type = constant.type().getTrueType();
                generateFieldInitializer(init, allocator, constant.name(), type, constant.value());
            }

            builder.addStaticBlock(init.build());
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private void generateFieldInitializer(
            final CodeBlock.Builder initializer,
            final NameAllocator allocator,
            final String name,
            final ThriftType tt,
            final ConstValueElement value) {

        tt.getTrueType().accept(new SimpleVisitor<Void>() {
            @Override
            public Void visitBuiltin(ThriftType builtinType) {
                CodeBlock init = renderConstValue(initializer, allocator, tt, value);
                initializer.addStatement("$L = $L", name, init);
                return null;
            }

            @Override
            public Void visitEnum(ThriftType userType) {
                CodeBlock item = renderConstValue(initializer, allocator, tt, value);

                initializer.addStatement("$L = $L", name, item);
                return null;
            }

            @Override
            public Void visitList(ThriftType.ListType listType) {
                List<ConstValueElement> list = (List<ConstValueElement>) value.value();
                String listName = allocator.newName("list", "list");
                ThriftType elementType = listType.elementType().getTrueType();
                TypeName elementTypeName = typeResolver.getJavaClass(elementType);
                TypeName listImplName = typeResolver.listOf(elementTypeName);

                if (list.isEmpty()) {
                    initializer.addStatement("$N = new $T()", name, listImplName);
                } else {
                    initializer.addStatement("$T $N = new $T($L)",
                            ParameterizedTypeName.get(TypeNames.LIST, elementTypeName),
                            listName,
                            listImplName,
                            list.size());

                    for (ConstValueElement element : list) {
                        CodeBlock item = renderConstValue(
                                initializer,
                                allocator,
                                elementType,
                                element);
                        initializer.addStatement("$N.add($L)", listName, item);
                    }

                    initializer.addStatement("$N = $N", name, listName);
                }
                return null;
            }

            @Override
            public Void visitSet(ThriftType.SetType setType) {
                List<ConstValueElement> set = (List<ConstValueElement>) value.value();
                String setName = allocator.newName("set", "set");
                ThriftType elementType = setType.elementType().getTrueType();
                TypeName elementTypeName = typeResolver.getJavaClass(elementType);
                TypeName setImplName = typeResolver.setOf(elementTypeName);

                if (set.isEmpty()) {
                    initializer.addStatement("$N = new $T()", name, setImplName);
                } else {
                    initializer.addStatement("$T $N = new $T($L)",
                            ParameterizedTypeName.get(TypeNames.SET, elementTypeName),
                            setName,
                            setImplName,
                            set.size());

                    for (ConstValueElement element : set) {
                        CodeBlock item = renderConstValue(
                                initializer,
                                allocator,
                                elementType,
                                element);
                        initializer.addStatement("$N.add($L)", setName, item);
                    }

                    initializer.addStatement("$N = $N", name, setName);
                }
                return null;
            }

            @Override
            public Void visitMap(ThriftType.MapType mapType) {
                Map<ConstValueElement, ConstValueElement> map =
                        (Map<ConstValueElement, ConstValueElement>) value.value();
                ThriftType keyType = mapType.keyType().getTrueType();
                ThriftType valueType = mapType.valueType().getTrueType();

                TypeName keyTypeName = typeResolver.getJavaClass(keyType);
                TypeName valueTypeName = typeResolver.getJavaClass(valueType);
                TypeName mapImplName = typeResolver.mapOf(keyTypeName, valueTypeName);

                if (map.isEmpty()) {
                    initializer.addStatement("$N = new $T()", name, mapImplName);
                } else {
                    String mapName = allocator.newName("map", "map");
                    initializer.addStatement("$T $N = new $T($L)",
                            ParameterizedTypeName.get(TypeNames.MAP, keyTypeName, valueTypeName),
                            mapName,
                            mapImplName,
                            map.size());

                    for (Map.Entry<ConstValueElement, ConstValueElement> entry : map.entrySet()) {
                        CodeBlock key = renderConstValue(initializer, allocator, keyType, entry.getKey());
                        CodeBlock value = renderConstValue(initializer, allocator, valueType, entry.getValue());

                        initializer.addStatement("$N.put($L, $L)", mapName, key, value);
                    }

                    initializer.addStatement("$N = $N", name, mapName);
                }
                return null;
            }

            @Override
            public Void visitUserType(ThriftType userType) {
                // TODO: this
                throw new UnsupportedOperationException("struct-type default values are not yet implemented");
            }

            @Override
            public Void visitTypedef(ThriftType.TypedefType typedefType) {
                throw new AssertionError("Should not be possible!");
            }
        });
    }

    private CodeBlock renderConstValue(
            final CodeBlock.Builder block,
            final NameAllocator allocator,
            final ThriftType type,
            final ConstValueElement value) {
        // TODO: Emit references to constants if kind == IDENTIFIER and it identifies an appropriately-typed const
        return type.accept(new ThriftType.Visitor<CodeBlock>() {
            @Override
            public CodeBlock visitBool() {
                String name;
                if (value.kind() == ConstValueElement.Kind.IDENTIFIER) {
                    name = "true".equals(value.value()) ? "true" : "false";
                } else if (value.kind() == ConstValueElement.Kind.INTEGER) {
                    name = ((Long) value.value()) == 0L ? "false" : "true";
                } else {
                    throw new AssertionError("Invalid boolean constant: " + value.value());
                }

                return CodeBlock.builder().add(name).build();
            }

            @Override
            public CodeBlock visitByte() {
                return CodeBlock.builder().add("(byte) $L", value.getAsInt()).build();
            }

            @Override
            public CodeBlock visitI16() {
                return CodeBlock.builder().add("(short) $L", value.getAsInt()).build();
            }

            @Override
            public CodeBlock visitI32() {
                return CodeBlock.builder().add("$L", value.getAsInt()).build();
            }

            @Override
            public CodeBlock visitI64() {
                return CodeBlock.builder().add("$L", value.getAsLong()).build();
            }

            @Override
            public CodeBlock visitDouble() {
                if (value.kind() == ConstValueElement.Kind.DOUBLE) {
                    return CodeBlock.builder().add("(double) $L", value.getAsDouble()).build();
                } else {
                    throw new AssertionError("Invalid double constant: " + value.value());
                }
            }

            @Override
            public CodeBlock visitString() {
                if (value.kind() == ConstValueElement.Kind.STRING) {
                    return CodeBlock.builder().add("$S", value.value()).build();
                } else {
                    throw new AssertionError("Invalid string constant: " + value.value());
                }
            }

            @Override
            public CodeBlock visitBinary() {
                throw new AssertionError("Binary literals are not supported");
            }

            @Override
            public CodeBlock visitVoid() {
                throw new AssertionError("Void literals are meaningless, what are you even doing");
            }

            @Override
            public CodeBlock visitEnum(final ThriftType tt) {
                EnumType enumType;
                try {
                    enumType = schema.findEnumByType(tt);
                } catch (NoSuchElementException e) {
                    throw new AssertionError("Missing enum type: " + tt.name());
                }

                EnumType.Member member;
                try {
                    if (value.kind() == ConstValueElement.Kind.INTEGER) {
                        member = enumType.findMemberById(value.getAsInt());
                    } else if (value.kind() == ConstValueElement.Kind.IDENTIFIER) {
                        member = enumType.findMemberByName(value.getAsString());
                    } else {
                        throw new AssertionError(
                                "Constant value kind " + value.kind() + " is not possibly an enum; validation bug");
                    }
                } catch (NoSuchElementException e) {
                    throw new IllegalStateException(
                            "No enum member in " + enumType.name() + " with value " + value.value());
                }

                return CodeBlock.builder()
                        .add("$T.$L", typeResolver.getJavaClass(tt), member.name())
                        .build();
            }

            @Override
            public CodeBlock visitList(ThriftType.ListType listType) {
                throw new IllegalStateException("nested lists not implemented");
            }

            @Override
            public CodeBlock visitSet(ThriftType.SetType setType) {
                throw new IllegalStateException("nested sets not implemented");
            }

            @Override
            public CodeBlock visitMap(ThriftType.MapType mapType) {
                throw new IllegalStateException("nested maps not implemented");
            }

            @Override
            public CodeBlock visitUserType(ThriftType userType) {
                throw new IllegalStateException("nested structs not implemented");
            }

            @Override
            public CodeBlock visitTypedef(ThriftType.TypedefType typedefType) {
                return null;
            }
        });
    }

    private static AnnotationSpec fieldAnnotation(Field field) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(ThriftField.class)
                .addMember("fieldId", "$L", field.id())
                .addMember("isRequired", "$L", field.required());

        String typedef = field.typedefName();
        if (!Strings.isNullOrEmpty(typedef)) {
            ann = ann.addMember("typedefName", "$S", typedef);
        }

        return ann.build();
    }

    private static AnnotationSpec generatedAnnotation() {
        return AnnotationSpec.builder(Generated.class)
                .addMember("value", "$S", ThriftyCodeGenerator.class.getCanonicalName())
                .addMember("date", "$S", DATE_FORMATTER.print(System.currentTimeMillis()))
                .build();
    }

    TypeSpec buildEnum(EnumType type) {
        ClassName enumClassName = ClassName.get(
                type.getNamespaceFor(NamespaceScope.JAVA),
                type.name());

        TypeSpec.Builder builder = TypeSpec.enumBuilder(type.name())
                .addModifiers(Modifier.PUBLIC)
                .addField(int.class, "code", Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(int.class, "code")
                        .addStatement("this.$N = $N", "code", "code")
                        .build());

        if (type.hasJavadoc()) {
            builder.addJavadoc(type.documentation());
        }

        MethodSpec.Builder fromCodeMethod = MethodSpec.methodBuilder("fromCode")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(enumClassName)
                .addParameter(int.class, "code")
                .beginControlFlow("switch (code)");

        for (EnumType.Member member : type.members()) {
            String name = member.name();

            int value = member.value();

            TypeSpec.Builder memberBuilder = TypeSpec.anonymousClassBuilder("$L", value);
            if (member.hasJavadoc()) {
                memberBuilder.addJavadoc(member.documentation());
            }

            builder.addEnumConstant(name, memberBuilder.build());

            fromCodeMethod.addStatement("case $L: return $N", value, name);
        }

        fromCodeMethod
                .addStatement("default: return null")
                .endControlFlow();

        builder.addMethod(fromCodeMethod.build());

        return builder.build();
    }
}

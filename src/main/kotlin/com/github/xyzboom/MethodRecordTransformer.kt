package com.github.xyzboom

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.*

class MethodRecordTransformer(
    classes: Set<String>,
    /**
     * methods here only used by transform d4j test classes
     */
    methods: Set<String>,
    args: Properties
) : ClassFileTransformer {
    private val classes: HashSet<String> = HashSet()
    private val methods: HashSet<String> = HashSet()

    init {
        if (!args.getProperty(KEY_ARGS_USE_SPECIFIED).toBoolean()) {
            this.classes.addAll(classes)
            for (methodName in methods) {
                this.classes.add(methodName.split("::")[0])
            }
        } else {
            val specifiedMethods = args.getProperty(KEY_ARGS_METHODS, "").split(",")
            this.methods.addAll(specifiedMethods)
            val specifiedClasses = args.getProperty(KEY_ARGS_CLASSES, "").split(",")
            this.classes.addAll(specifiedClasses)
            for (methodName in specifiedMethods) {
                this.classes.add(methodName.split("::")[0])
            }
            this.classes.remove("")
            this.methods.remove("")
        }
        // hard code to avoid print exception classes
        this.classes.removeIf { "Exception" in it }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Use specified but not classes in [D4J_FILE]
         */
        private const val KEY_ARGS_USE_SPECIFIED = "args.use.specified"
        private const val KEY_ARGS_CLASSES = "args.classes"
        private const val KEY_ARGS_METHODS = "args.methods"
        val classNameWhiteList = listOf(
            MethodRecordAgent::class.java,
            MethodRecordTransformer::class.java,
            Companion::class.java,
        ).map(Type::getInternalName).toHashSet()
    }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray?
    ): ByteArray? {
        val classLoader = loader ?: ClassLoader.getSystemClassLoader()
        if (className == null || className in classNameWhiteList) {
            return null
        }
        val name = className.replace("/", ".")
        if (classes.isNotEmpty() && name !in classes) {
            return null
        }
        println("Transforming class: $className")
        try {
            val classReader = ClassReader(
                classLoader.getResourceAsStream(className.replace(".", "/") + ".class")
            )
            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            val classNode = ClassNode(ASM9)
            classReader.accept(classNode, ClassReader.EXPAND_FRAMES)
            transformClass(classNode)
            classNode.accept(classWriter)
            return classWriter.toByteArray()
        } catch (e: Exception) {
            logger.error { e.stackTraceToString() }
        }
        return classfileBuffer
    }

    private fun transformClass(classNode: ClassNode) {
        for (method in classNode.methods) {
            try {
                transformMethod(method, classNode.name)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun transformMethod(methodNode: MethodNode, owner: String) {
        val methodName = "${owner.replace("/", ".")}::${methodNode.name}"
        if (methods.isNotEmpty() &&
            methodName !in methods) {
            return
        }
        if (methodNode.name == "<clinit>" || methodNode.name == "<init>") {
            return
        }
        if (methodNode.access and ACC_ABSTRACT != 0) {
            return
        }
        val printNameInsnList = InsnList().apply {
            add(LdcInsnNode(methodName))
            add(
                MethodInsnNode(
                    AdviceAdapter.INVOKESTATIC,
                    Type.getInternalName(MethodRecorder::class.java),
                    MethodRecorder::record.name,
                    "(Ljava/lang/String;)V"
                )
            )
        }
        methodNode.instructions.first?.apply {
            methodNode.instructions.insertBefore(this, printNameInsnList)
        } ?: methodNode.instructions.insert(printNameInsnList)
    }

}
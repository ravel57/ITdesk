package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Воркер, исполняющий Groovy-плагин в отдельном процессе.
 * <p>
 * Ограничения внутри JVM применяются всегда, а не только при наличии bubblewrap,
 * а их строгость зависит от системного свойства {@code uldesk.plugin.isolation},
 * которое проставляет {@link GroovyPluginRuntime}:
 * <ul>
 *     <li>{@code os} — процесс запущен под bubblewrap, файловая система и сеть
 *     уже отрезаны ядром; здесь блокируются запуск процессов, рефлексия,
 *     загрузка классов и JNDI;</li>
 *     <li>{@code process} — изоляции ОС нет, поэтому дополнительно блокируются
 *     файловые классы, java.nio.file, java.nio.channels и java.net,
 *     чтобы плагин не читал файлы приложения и не ходил в сеть мимо брокера.</li>
 * </ul>
 * Ограничения намеренно точечные: блокируются конкретные опасные классы и пакеты,
 * а не всё подряд, иначе ломаются работающие плагины.
 * <p>
 * Важное ограничение подхода: проверки на этапе компиляции и фильтрующий загрузчик
 * классов не перехватывают динамическую диспетчеризацию Groovy
 * (например, {@code "cmd".execute()}). Для недоверенных плагинов сюда нужно
 * добавить org.kohsuke:groovy-sandbox — SandboxTransformer с белым списком
 * получателей и методов.
 */
public class GroovySandboxMain {

	private static final int MAX_RESULT_BYTES = 1_048_576;

	/** Точные имена классов, запрещённых в любом режиме. */
	private static final List<String> DENIED_CLASSES = List.of(
			"java.lang.Runtime",
			"java.lang.ProcessBuilder",
			"java.lang.Process",
			"java.lang.ClassLoader",
			"java.lang.invoke.MethodHandles$Lookup",
			"java.beans.Expression",
			"java.beans.Statement",
			"groovy.lang.GroovyShell",
			"groovy.lang.GroovyClassLoader",
			"groovy.util.Eval"
	);

	/** Пакеты, запрещённые в любом режиме. */
	private static final List<String> DENIED_PACKAGES = List.of(
			"java.lang.reflect",
			"java.lang.invoke",
			"java.security",
			"javax.naming",
			"javax.script",
			"java.sql",
			"javax.sql",
			"sun",
			"jdk.internal",
			"org.springframework",
			"ru.ravel.ItDesk"
	);

	/** Прямой доступ к файлам — только когда его не отрезала ОС. */
	private static final List<String> DENIED_CLASSES_WITHOUT_OS_SANDBOX = List.of(
			"java.io.File",
			"java.io.FileInputStream",
			"java.io.FileOutputStream",
			"java.io.FileReader",
			"java.io.FileWriter",
			"java.io.RandomAccessFile",
			"java.io.FileDescriptor"
	);

	/** Сеть в обход брокера — только когда её не отрезала ОС. */
	private static final List<String> DENIED_PACKAGES_WITHOUT_OS_SANDBOX = List.of(
			"java.nio.file",
			"java.nio.channels",
			"java.net"
	);

	/**
	 * Исключения из денилиста: без этого плагин не сможет обратиться к брокеру,
	 * который остаётся его единственным легальным выходом наружу.
	 */
	private static final List<String> ALLOWED_CLASSES = List.of(
			"ru.ravel.ItDesk.plugins.PluginSandboxApi",
			/*
			 * Groovy introspection resolves this JDK type while constructing the
			 * script MetaClass. It remains forbidden to plugin source by
			 * SecureASTCustomizer; this exception only lets the trusted Groovy
			 * runtime link successfully through RestrictedClassLoader.
			 */
			"java.lang.invoke.MethodHandles$Lookup"
	);


	public static void main(String[] args) throws Exception {
		Map<String, String> arguments = parseArgs(args);

		String pluginDir = arguments.get("--plugin-dir");
		String entrypoint = arguments.get("--entrypoint");
		String handler = arguments.get("--handler");

		if (pluginDir == null || entrypoint == null || handler == null) {
			throw new IllegalArgumentException("--plugin-dir, --entrypoint and --handler are required");
		}
		if (!handler.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}")) {
			throw new IllegalArgumentException("Invalid handler name");
		}

		boolean osSandbox = "os".equalsIgnoreCase(
				System.getProperty("uldesk.plugin.isolation", "process")
		);

		ObjectMapper objectMapper = new ObjectMapper();

		Map<String, Object> context = objectMapper.readValue(
				System.in,
				new TypeReference<Map<String, Object>>() {
				}
		);

		File groovyFile = resolveEntrypoint(pluginDir, entrypoint);

		/*
		 * stdout — это транспорт результата. Всё, что печатает сам плагин,
		 * уводится в stderr, иначе ответ перестаёт быть валидным JSON.
		 */
		PrintStream resultStream = System.out;
		System.setOut(System.err);

		Object result;
		try {
			GroovyShell shell = new GroovyShell(
					new RestrictedClassLoader(GroovySandboxMain.class.getClassLoader(), osSandbox),
					new Binding(),
					compilerConfiguration(osSandbox)
			);

			Object pluginInstance = shell.evaluate(groovyFile);
			if (pluginInstance == null) {
				throw new IllegalStateException("Groovy plugin returned null");
			}

			String brokerSocket = System.getenv("ULDESK_PLUGIN_BROKER_SOCKET");
			String brokerToken = System.getenv("ULDESK_PLUGIN_BROKER_TOKEN");

			if (brokerSocket != null
					&& !brokerSocket.isBlank()
					&& brokerToken != null
					&& !brokerToken.isBlank()) {

				context.put(
						"sandbox",
						new PluginSandboxApi(
								Path.of(brokerSocket),
								brokerToken
						)
				);
			}

			result = InvokerHelper.invokeMethod(pluginInstance, handler, context);
		} finally {
			System.setOut(resultStream);
		}

		byte[] payload = objectMapper.writeValueAsBytes(result);
		if (payload.length > MAX_RESULT_BYTES) {
			throw new IllegalStateException("Plugin result exceeds 1 MiB");
		}
		resultStream.write(payload);
		resultStream.flush();
	}


	private static File resolveEntrypoint(String pluginDir, String entrypoint) throws Exception {
		Path root = Paths.get(pluginDir).toRealPath();
		Path candidate = root.resolve(entrypoint).normalize();
		if (!candidate.startsWith(root)
				|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(candidate)) {
			throw new IllegalArgumentException("Groovy entrypoint must be inside the plugin directory");
		}
		Path real = candidate.toRealPath();
		if (!real.startsWith(root)) {
			throw new IllegalArgumentException("Groovy entrypoint escapes the plugin directory");
		}
		return real.toFile();
	}


	/**
	 * Названия методов соответствуют Groovy 4 (Spring Boot 3.x).
	 * В Groovy 2 они назывались setImportsBlacklist / setReceiversBlackList.
	 */
	private static CompilerConfiguration compilerConfiguration(boolean osSandbox) {
		SecureASTCustomizer secure = new SecureASTCustomizer();

		/* Проверять не только import, но и полные имена классов в коде. */
		secure.setIndirectImportCheckEnabled(true);
		secure.setPackageAllowed(false);

		List<String> deniedClasses = deniedClasses(osSandbox);
		List<String> deniedPackages = deniedPackages(osSandbox);

		secure.setDisallowedImports(deniedClasses);
		secure.setDisallowedStaticImports(deniedClasses);
		secure.setDisallowedStarImports(deniedPackages);
		secure.setDisallowedStaticStarImports(deniedPackages);

		/*
		 * java.lang.System намеренно не запрещён: System.exit не даёт эскалации
		 * (процесс всё равно одноразовый), а запрет ломает currentTimeMillis
		 * в существующих плагинах.
		 */
		secure.setDisallowedReceivers(List.of(
				"java.lang.Runtime",
				"java.lang.ProcessBuilder",
				"java.lang.Class",
				"java.lang.ClassLoader",
				"java.lang.invoke.MethodHandles$Lookup",
				"groovy.lang.GroovyShell",
				"groovy.util.Eval"
		));

		CompilerConfiguration configuration = new CompilerConfiguration();
		configuration.addCompilationCustomizers(secure);
		configuration.setSourceEncoding(StandardCharsets.UTF_8.name());
		return configuration;
	}


	private static List<String> deniedClasses(boolean osSandbox) {
		List<String> denied = new ArrayList<>(DENIED_CLASSES);
		if (!osSandbox) {
			denied.addAll(DENIED_CLASSES_WITHOUT_OS_SANDBOX);
		}
		return denied;
	}


	private static List<String> deniedPackages(boolean osSandbox) {
		List<String> denied = new ArrayList<>(DENIED_PACKAGES);
		if (!osSandbox) {
			denied.addAll(DENIED_PACKAGES_WITHOUT_OS_SANDBOX);
		}
		return denied;
	}


	/**
	 * Отсекает загрузку запрещённых классов кодом плагина.
	 * На уже загруженные классы рантайма не влияет: PluginSandboxApi
	 * продолжает пользоваться java.net изнутри своего загрузчика.
	 */
	private static final class RestrictedClassLoader extends ClassLoader {

		private final List<String> deniedClasses;
		private final List<String> deniedPackages;


		private RestrictedClassLoader(ClassLoader parent, boolean osSandbox) {
			super(parent);
			this.deniedClasses = deniedClasses(osSandbox);
			this.deniedPackages = deniedPackages(osSandbox);
		}


		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (isDenied(name)) {
				throw new ClassNotFoundException(
						"Class is denied by the plugin sandbox policy: " + name
				);
			}
			return super.loadClass(name, resolve);
		}


		private boolean isDenied(String name) {
			if (name == null || name.isBlank()) {
				return false;
			}
			if (ALLOWED_CLASSES.contains(name)) {
				return false;
			}
			if (deniedClasses.contains(name)) {
				return true;
			}
			for (String deniedPackage : deniedPackages) {
				if (name.startsWith(deniedPackage + ".")) {
					return true;
				}
			}
			return false;
		}
	}


	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> result = new HashMap<>();

		for (int i = 0; i < args.length - 1; i += 2) {
			result.put(args[i], args[i + 1]);
		}

		return result;
	}
}

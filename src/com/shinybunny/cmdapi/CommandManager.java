package com.shinybunny.cmdapi;

import com.shinybunny.cmdapi.annotations.*;
import com.shinybunny.cmdapi.arguments.*;
import com.shinybunny.cmdapi.exceptions.*;
import com.shinybunny.cmdapi.utils.InputReader;

import javax.naming.NoPermissionException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A command manager holds all storage and mapping of commands, annotation adapters and argument adapters.
 * <br/>
 * Multiple instances of this class means multiple command systems, if you need that for some reason.
 * Otherwise, just use the CommandAPI to use the default command manager.<br/>
 * Extending this class is fine, and recommended if you need any custom mechanics.
 */
public class CommandManager {

    /**
     * Annotation types to {@link AnnotationAdapter}s map
     */
    protected Map<Class<? extends Annotation>, AnnotationAdapter<?>> annotationAdapters = new HashMap<>();
    /**
     * Object types to {@link ArgumentAdapter}s map
     */
    protected Map<Class<?>, ArgumentAdapter<?>> argumentAdapters = new HashMap<>();
    /**
     * All registered commands
     */
    protected List<CommandBase> commands = new ArrayList<>();
    /**
     * Command register listeners. will all {@link Consumer#accept(Object)} with the new command registered.
     */
    protected List<Consumer<CommandBase>> registerCommandListeners = new ArrayList<>();

    public CommandManager() {
        registerDefaults();
    }

    protected void registerDefaults() {
        registerAnnotationAdapter(new Default.Adapter());
        registerAnnotationAdapter(new Arg.Adapter());
        registerAnnotationAdapter(new Range.Adapter());
        registerAnnotationAdapter(new Optional.Adapter());
        registerAnnotationAdapter(AnnotationAdapter.dummy(MultiWord.class));

        registerArgumentAdapter(new BooleanAdapter());
        registerArgumentAdapter(new NumberAdapter());
        registerArgumentAdapter(new StringAdapter());
        registerArgumentAdapter(new SenderAdapter());
    }

    /**
     * Gets the {@link AnnotationAdapter} of the provided type.
     * @param annotationType The annotation class
     * @param <A> The generic type of annotation
     * @return The registered annotation adapter of the specified type, or <code>null</code> if not found.
     */
    public <A extends Annotation> AnnotationAdapter<A> getAnnotationAdapter(Class<A> annotationType) {
        for (Map.Entry<Class<? extends Annotation>, AnnotationAdapter<?>> e : annotationAdapters.entrySet()) {
            if (e.getKey() == annotationType) return (AnnotationAdapter<A>) e.getValue();
        }
        return null;
    }

    /**
     * Gets the {@link ArgumentAdapter} of the provided type. Will try matching the types exactly, by t == type, and if none found will use {@link Class#isAssignableFrom(Class)}
     * @param type The object type class
     * @param <T> The generic type of object
     * @return The registered argument adapter of the specified type, or <code>null</code> if not found.
     */
    public <T> ArgumentAdapter<T> getArgumentAdapter(Class<T> type) {
        ArgumentAdapter<T> adapter = (ArgumentAdapter<T>) argumentAdapters.get(type);
        if (adapter != null) {
            return adapter;
        } else {
            for (Map.Entry<Class<?>, ArgumentAdapter<?>> e : argumentAdapters.entrySet()) {
                if (e.getKey().isAssignableFrom(type)) return (ArgumentAdapter<T>) e.getValue();
            }
        }
        return null;
    }


    /**
     * Adds a listener for registering commands. Calls {@link Consumer#accept(Object)} every time a new {@link MethodCommand} is registered to the api.
     * @param listener The consumer to handle the new command object.
     */
    public void addCommandRegisterListener(Consumer<CommandBase> listener) {
        registerCommandListeners.add(listener);
    }

    /**
     * Register an {@link AnnotationAdapter} to the api.
     */
    public void registerAnnotationAdapter(AnnotationAdapter<?> adapter) {
        annotationAdapters.put(adapter.getAnnotationType(), adapter);
    }

    /**
     * Register an {@link ArgumentAdapter} to the api.
     */
    public void registerArgumentAdapter(ArgumentAdapter<?> adapter) {
        argumentAdapters.put(adapter.getType(), adapter);
    }

    /**
     * Gets the command uses the specified alias. Note that if more than one command has that alias (which is NOT supported, be careful!), only the first iterated command will be returned.
     * @param alias The alias to search for, and try matching it with all command names or aliases.
     * @return The command having that alias, or <code>null</code> if none found.
     */
    public CommandBase getCommand(String alias) {
        for (CommandBase cmd : commands) {
            if (cmd.getName().equalsIgnoreCase(alias) || cmd.getAliases().contains(alias.toLowerCase())) {
                return cmd;
            }
        }

        return null;
    }

    /**
     * Registers a new command holder. A command holder is a class containing all methods to parse as commands.<br/>
     * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;In case the provided object is annotated with {@link com.shinybunny.cmdapi.annotations.Command},
     * it will be treated as a tree command and all of its methods will be its sub commands.<br/>
     * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;In case it doesn't,
     * all methods will be individual commands.
     * <br/>
     * To exclude a method, you can do one of the following:
     * <ol>
     *     <li>Make the method <code>static</code>. Static methods will not be registered.</li>
     *     <li>Annotate the method with <code>@</code>{@link DontRegister}</li>
     * </ol>
     * @param holder The instance of a class to register.
     * @exception IncompatibleAnnotationException if an annotation of some argument is not compatible with its type. For example, {@link Range @Range} for a non-numerical value.
     * @exception NoAdapterFoundException for any annotation or argument of a type that has no matching registered adapter.
     */
    public void register(Object holder) throws IncompatibleAnnotationException, NoAdapterFoundException {
        if (holder.getClass().isAnnotationPresent(Command.class)) {
            // this object is annotated with Settings, so it's a tree command!
            registerTree(holder);
        } else {
            List<CommandBase> cmds = createCommands(holder);
            commands.addAll(cmds);
            // run all listeners with all new registered commands.
            cmds.forEach((c) -> {
                registerCommandListeners.forEach((l) -> {
                    l.accept(c);
                });
            });
        }
    }

    /**
     * Registers a new instance of the provided class.
     * @param cls The class to instantiate, using {@link Class#newInstance()}
     */
    public void register(Class<?> cls) {
        try {
            registerSafe(cls.newInstance());
        } catch (IllegalAccessException | InstantiationException e) {
            e.printStackTrace();
        }
    }

    protected void registerTree(Object holder) throws IncompatibleAnnotationException, NoAdapterFoundException {
        commands.add(new MethodCommand(this,holder, holder.getClass().getAnnotation(Command.class)));
    }

    public void registerSafe(Object holder) {
        try {
            register(holder);
        } catch (IncompatibleAnnotationException | NoAdapterFoundException e) {
            e.printStackTrace();
        }
    }

    protected List<CommandBase> createCommands(Object holder) throws IncompatibleAnnotationException, NoAdapterFoundException {
        List<CommandBase> cmds = new ArrayList<>();
        for (Method m : holder.getClass().getDeclaredMethods()) {
            // registering only non-static methods, without a DontRegister annotation.
            if (!Modifier.isStatic(m.getModifiers()) && !m.isAnnotationPresent(DontRegister.class)) {
                cmds.add(new MethodCommand(this,m.getName(), m, holder));
            }
        }
        return cmds;
    }

    /**
     * Get all commands registered in the CommandAPI
     * @return A list of all commands. This list is not a copy, so it's possible to modify it.
     */
    public List<CommandBase> getCommands() {
        return commands;
    }

    protected List<MethodCommand> createSubCommands(Object holder) throws IncompatibleAnnotationException, NoAdapterFoundException {
        List<MethodCommand> cmds = new ArrayList<>();

        for (Method m : holder.getClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) && !m.isAnnotationPresent(DontRegister.class)) {
                cmds.add(new MethodCommand(this,m.getName(), m, holder));
            }
        }

        return cmds;
    }

    /**
     * Create a command programmatically
     * @param name The command name
     * @return A new {@link DynamicCommand.Builder} to build the command.
     */
    public DynamicCommand.Builder createCommand(String name) {
        return new DynamicCommand.Builder(name);
    }

    /**
     * Parses, processes and validates a command input.<br/>
     * The provided input should start with the command alias, following with its arguments separated by spaces.
     * @param sender The {@link Sender} who executed the command.
     * @param input The command string the sender runs.
     * @return A new {@link ParseResults} object containing a mapping of all parsed information.
     * @throws UnknownCommandException if the provided command alias is not found.
     * @throws InvalidArgumentException if an argument validation exception occurs.
     * @throws MissingArgumentException if a required argument is not provided.
     * @throws CommandParseException if an argument parsing exception occurs.
     */
    public ParseResults parse(Sender sender, String input) throws UnknownCommandException, InvalidArgumentException, MissingArgumentException, CommandParseException, NoPermissionException {
        String alias;
        String args;
        if (input.contains(" ")) {
            alias = input.substring(0, input.indexOf(' '));
            args = input.substring(input.indexOf(' ') + 1);
        } else {
            alias = input;
            args = "";
        }
        CommandBase cmd = getCommand(alias);
        if (cmd == null) {
            throw new UnknownCommandException(alias);
        }
        CommandContext ctx = new CommandContext(alias,cmd,sender,input);
        InputReader reader = new InputReader(args);
        return parse(ctx,reader);
    }

    protected ParseResults parse(CommandContext ctx, InputReader reader) throws MissingArgumentException, CommandParseException, InvalidArgumentException, NoPermissionException {
        ctx.getCommand().validateUse(ctx);
        for (Argument arg : ctx.getCommand().getArguments()) {
            // get the out of syntax value by default
            Object value = arg.getOutOfSyntax(ctx);
            if (!arg.isSyntax()) {
                // one annotation adapter decided this argument is not syntax,
                // so let's process hoping that annotation adapter will give us a value to work with
                value = arg.process(value,ctx);
            }
            if (reader.canRead()) {
                // there is more to read, so we can parse the argument
                if (value == null) {
                    // the argument is a syntax, so let's parse it from the InputReader
                    value = arg.parse(reader, ctx);
                    reader.skipSpace();
                    // then, we need to process the value in all of its annotations
                    Object newVal = arg.process(value, ctx);
                    if (newVal != null) {
                        value = newVal;
                    }
                    if (value == null && !arg.isNullable()) {
                        // after all of that, we still got a null value, and the argument adapter doesn't allow nulls. so rip
                        throw new MissingArgumentException(arg);
                    }
                    if (arg.getType() == MethodCommand.class) {
                        // oh waaait.. it's actually a sub command... so we recall the parse() method again, with the sub command in the context
                        ctx.setCommand((MethodCommand) value);
                        return parse(ctx,reader);
                    }
                }
                ctx.addArgument(new ParseResults.Entry(arg,value));
            } else {
                // there is no more to read, but we still got arguments left...
                // so we have to make sure they do not come out as nulls we don't want.
                if (value == null) {
                    value = arg.getDefaultValue();
                }
                value = arg.process(value,ctx);
                if (value == null && !arg.isNullable() && arg.isRequired()) {
                    throw new MissingArgumentException(arg);
                }

                ctx.addArgument(new ParseResults.Entry(arg,value));
            }
        }
        return ctx.getResults();
    }

    public void addCommand(CommandBase cmd) {
        commands.add(cmd);
    }

}

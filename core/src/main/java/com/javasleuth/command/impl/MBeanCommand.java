package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.regex.Pattern;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

public class MBeanCommand implements Command {
    private final Instrumentation instrumentation;
    private final MBeanServer mbeanServer;

    public MBeanCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        String subCommand = args.length > 1 ? args[1].toLowerCase() : "list";

        switch (subCommand) {
            case "list":
            case "ls":
                return listMBeans(args);
            case "info":
                return getMBeanInfo(args);
            case "get":
                return getMBeanAttribute(args);
            case "set":
                return setMBeanAttribute(args);
            case "invoke":
                return invokeMBeanOperation(args);
            case "domains":
                return listDomains();
            case "search":
                return searchMBeans(args);
            default:
                // If not a known subcommand, treat as ObjectName pattern for listing
                return listMBeans(new String[]{"list", subCommand});
        }
    }

    private String listMBeans(String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MBean List ===\n");

        try {
            String pattern = args.length > 2 ? args[2] : "*:*";
            ObjectName objectName = new ObjectName(pattern);
            Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(objectName, null);

            sb.append("Pattern: ").append(pattern).append("\n");
            sb.append("Found ").append(mbeans.size()).append(" MBeans:\n\n");

            // Group by domain
            Map<String, List<ObjectInstance>> domainGroups = new TreeMap<>();
            for (ObjectInstance mbean : mbeans) {
                String domain = mbean.getObjectName().getDomain();
                domainGroups.computeIfAbsent(domain, k -> new ArrayList<>()).add(mbean);
            }

            for (Map.Entry<String, List<ObjectInstance>> entry : domainGroups.entrySet()) {
                sb.append("Domain: ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(" MBeans)\n");

                for (ObjectInstance mbean : entry.getValue()) {
                    ObjectName name = mbean.getObjectName();
                    sb.append("  ").append(name.getKeyPropertyListString()).append("\n");
                    sb.append("    Class: ").append(mbean.getClassName()).append("\n");

                    try {
                        MBeanInfo info = mbeanServer.getMBeanInfo(name);
                        sb.append("    Attributes: ").append(info.getAttributes().length);
                        sb.append(", Operations: ").append(info.getOperations().length);
                        sb.append(", Notifications: ").append(info.getNotifications().length).append("\n");
                    } catch (Exception e) {
                        sb.append("    [Info unavailable: ").append(e.getMessage()).append("]\n");
                    }
                    sb.append("\n");
                }
            }

        } catch (Exception e) {
            sb.append("Error listing MBeans: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String getMBeanInfo(String[] args) {
        if (args.length < 3) {
            return "Usage: mbean info <ObjectName>\n" +
                   "Example: mbean info java.lang:type=Memory";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== MBean Information ===\n");

        try {
            ObjectName objectName = new ObjectName(args[2]);
            if (!mbeanServer.isRegistered(objectName)) {
                return "MBean not found: " + args[2];
            }

            MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
            sb.append("ObjectName: ").append(objectName).append("\n");
            sb.append("ClassName: ").append(info.getClassName()).append("\n");
            sb.append("Description: ").append(info.getDescription()).append("\n\n");

            // Attributes
            MBeanAttributeInfo[] attributes = info.getAttributes();
            if (attributes.length > 0) {
                sb.append("Attributes (").append(attributes.length).append("):\n");
                for (MBeanAttributeInfo attr : attributes) {
                    sb.append("  ").append(attr.getName()).append(" : ").append(attr.getType());
                    if (attr.isReadable()) sb.append(" [R]");
                    if (attr.isWritable()) sb.append(" [W]");
                    sb.append("\n");
                    if (attr.getDescription() != null && !attr.getDescription().isEmpty()) {
                        sb.append("    ").append(attr.getDescription()).append("\n");
                    }
                }
                sb.append("\n");
            }

            // Operations
            MBeanOperationInfo[] operations = info.getOperations();
            if (operations.length > 0) {
                sb.append("Operations (").append(operations.length).append("):\n");
                for (MBeanOperationInfo op : operations) {
                    sb.append("  ").append(op.getName()).append("(");

                    MBeanParameterInfo[] params = op.getSignature();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getType()).append(" ").append(params[i].getName());
                    }

                    sb.append(") : ").append(op.getReturnType()).append("\n");
                    if (op.getDescription() != null && !op.getDescription().isEmpty()) {
                        sb.append("    ").append(op.getDescription()).append("\n");
                    }
                }
                sb.append("\n");
            }

            // Notifications
            MBeanNotificationInfo[] notifications = info.getNotifications();
            if (notifications.length > 0) {
                sb.append("Notifications (").append(notifications.length).append("):\n");
                for (MBeanNotificationInfo notif : notifications) {
                    sb.append("  ").append(notif.getName()).append("\n");
                    sb.append("    Types: ").append(Arrays.toString(notif.getNotifTypes())).append("\n");
                    if (notif.getDescription() != null && !notif.getDescription().isEmpty()) {
                        sb.append("    ").append(notif.getDescription()).append("\n");
                    }
                }
            }

        } catch (Exception e) {
            sb.append("Error getting MBean info: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String getMBeanAttribute(String[] args) {
        if (args.length < 4) {
            return "Usage: mbean get <ObjectName> <AttributeName>\n" +
                   "Example: mbean get java.lang:type=Memory HeapMemoryUsage";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== MBean Attribute ===\n");

        try {
            ObjectName objectName = new ObjectName(args[2]);
            String attributeName = args[3];

            if (!mbeanServer.isRegistered(objectName)) {
                return "MBean not found: " + args[2];
            }

            Object value = mbeanServer.getAttribute(objectName, attributeName);

            sb.append("ObjectName: ").append(objectName).append("\n");
            sb.append("Attribute: ").append(attributeName).append("\n");
            sb.append("Type: ").append(value != null ? value.getClass().getName() : "null").append("\n");
            sb.append("Value:\n");
            sb.append(formatAttributeValue(value, "  "));

        } catch (AttributeNotFoundException e) {
            sb.append("Attribute not found: ").append(args[3]);
        } catch (Exception e) {
            sb.append("Error getting attribute: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String setMBeanAttribute(String[] args) {
        if (args.length < 5) {
            return "Usage: mbean set <ObjectName> <AttributeName> <Value>\n" +
                   "Example: mbean set java.util.logging:type=Logging .level INFO";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Set MBean Attribute ===\n");

        try {
            ObjectName objectName = new ObjectName(args[2]);
            String attributeName = args[3];
            String valueStr = args[4];

            if (!mbeanServer.isRegistered(objectName)) {
                return "MBean not found: " + args[2];
            }

            // Get attribute info to determine type
            MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
            MBeanAttributeInfo attrInfo = null;
            for (MBeanAttributeInfo attr : info.getAttributes()) {
                if (attr.getName().equals(attributeName)) {
                    attrInfo = attr;
                    break;
                }
            }

            if (attrInfo == null) {
                return "Attribute not found: " + attributeName;
            }

            if (!attrInfo.isWritable()) {
                return "Attribute is not writable: " + attributeName;
            }

            // Convert value to appropriate type
            Object value = convertStringToType(valueStr, attrInfo.getType());
            Attribute attribute = new Attribute(attributeName, value);

            mbeanServer.setAttribute(objectName, attribute);

            sb.append("Attribute set successfully!\n");
            sb.append("ObjectName: ").append(objectName).append("\n");
            sb.append("Attribute: ").append(attributeName).append("\n");
            sb.append("New Value: ").append(value).append("\n");

        } catch (Exception e) {
            sb.append("Error setting attribute: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String invokeMBeanOperation(String[] args) {
        if (args.length < 4) {
            return "Usage: mbean invoke <ObjectName> <OperationName> [param1] [param2] ...\n" +
                   "Example: mbean invoke java.lang:type=Memory gc";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Invoke MBean Operation ===\n");

        try {
            ObjectName objectName = new ObjectName(args[2]);
            String operationName = args[3];

            if (!mbeanServer.isRegistered(objectName)) {
                return "MBean not found: " + args[2];
            }

            // Collect parameters
            String[] params = new String[args.length - 4];
            System.arraycopy(args, 4, params, 0, params.length);

            // Find matching operation
            MBeanInfo info = mbeanServer.getMBeanInfo(objectName);
            MBeanOperationInfo targetOp = null;
            for (MBeanOperationInfo op : info.getOperations()) {
                if (op.getName().equals(operationName) && op.getSignature().length == params.length) {
                    targetOp = op;
                    break;
                }
            }

            if (targetOp == null) {
                return "Operation not found or parameter count mismatch: " + operationName;
            }

            // Convert parameters
            Object[] convertedParams = new Object[params.length];
            String[] signature = new String[params.length];
            MBeanParameterInfo[] paramInfo = targetOp.getSignature();

            for (int i = 0; i < params.length; i++) {
                signature[i] = paramInfo[i].getType();
                convertedParams[i] = convertStringToType(params[i], signature[i]);
            }

            // Invoke operation
            Object result = mbeanServer.invoke(objectName, operationName, convertedParams, signature);

            sb.append("Operation invoked successfully!\n");
            sb.append("ObjectName: ").append(objectName).append("\n");
            sb.append("Operation: ").append(operationName).append("\n");
            sb.append("Parameters: ").append(Arrays.toString(params)).append("\n");
            sb.append("Return Type: ").append(targetOp.getReturnType()).append("\n");
            sb.append("Result:\n");
            sb.append(formatAttributeValue(result, "  "));

        } catch (Exception e) {
            sb.append("Error invoking operation: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String listDomains() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MBean Domains ===\n");

        try {
            String[] domains = mbeanServer.getDomains();
            Arrays.sort(domains);

            sb.append("Total domains: ").append(domains.length).append("\n\n");

            for (String domain : domains) {
                Set<ObjectInstance> mbeans = mbeanServer.queryMBeans(new ObjectName(domain + ":*"), null);
                sb.append(String.format("%-30s : %d MBeans\n", domain, mbeans.size()));
            }

        } catch (Exception e) {
            sb.append("Error listing domains: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String searchMBeans(String[] args) {
        if (args.length < 3) {
            return "Usage: mbean search <pattern>\n" +
                   "Example: mbean search Memory";
        }

        String searchPattern = args[2];
        StringBuilder sb = new StringBuilder();
        sb.append("=== MBean Search ===\n");
        sb.append("Search pattern: ").append(searchPattern).append("\n\n");

        try {
            Pattern pattern = Pattern.compile(searchPattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE);
            Set<ObjectInstance> allMBeans = mbeanServer.queryMBeans(new ObjectName("*:*"), null);
            List<ObjectInstance> matches = new ArrayList<>();

            for (ObjectInstance mbean : allMBeans) {
                String objectNameStr = mbean.getObjectName().toString();
                String className = mbean.getClassName();

                if (pattern.matcher(objectNameStr).find() || pattern.matcher(className).find()) {
                    matches.add(mbean);
                }
            }

            if (matches.isEmpty()) {
                sb.append("No MBeans found matching: ").append(searchPattern);
            } else {
                sb.append("Found ").append(matches.size()).append(" matching MBeans:\n\n");

                for (ObjectInstance mbean : matches) {
                    sb.append("ObjectName: ").append(mbean.getObjectName()).append("\n");
                    sb.append("Class: ").append(mbean.getClassName()).append("\n");
                    sb.append("\n");
                }
            }

        } catch (Exception e) {
            sb.append("Error searching MBeans: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private String formatAttributeValue(Object value, String indent) {
        if (value == null) {
            return indent + "null\n";
        }

        StringBuilder sb = new StringBuilder();

        if (value instanceof CompositeData) {
            CompositeData cd = (CompositeData) value;
            sb.append(indent).append("CompositeData:\n");
            for (String key : cd.getCompositeType().keySet()) {
                sb.append(indent).append("  ").append(key).append(" = ");
                Object subValue = cd.get(key);
                if (subValue != null && subValue.toString().contains("\n")) {
                    sb.append("\n").append(formatAttributeValue(subValue, indent + "    "));
                } else {
                    sb.append(subValue).append("\n");
                }
            }
        } else if (value instanceof TabularData) {
            TabularData td = (TabularData) value;
            sb.append(indent).append("TabularData (").append(td.size()).append(" rows):\n");
            for (Object rowValue : td.values()) {
                sb.append(formatAttributeValue(rowValue, indent + "  "));
            }
        } else if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            sb.append(indent).append("Array[").append(array.length).append("]:\n");
            for (int i = 0; i < Math.min(array.length, 10); i++) {
                sb.append(indent).append("  [").append(i).append("] = ").append(array[i]).append("\n");
            }
            if (array.length > 10) {
                sb.append(indent).append("  ... (").append(array.length - 10).append(" more)\n");
            }
        } else {
            sb.append(indent).append(value.toString()).append("\n");
        }

        return sb.toString();
    }

    private Object convertStringToType(String value, String type) {
        switch (type) {
            case "java.lang.String":
                return value;
            case "boolean":
            case "java.lang.Boolean":
                return Boolean.parseBoolean(value);
            case "int":
            case "java.lang.Integer":
                return Integer.parseInt(value);
            case "long":
            case "java.lang.Long":
                return Long.parseLong(value);
            case "double":
            case "java.lang.Double":
                return Double.parseDouble(value);
            case "float":
            case "java.lang.Float":
                return Float.parseFloat(value);
            default:
                return value; // Default to string
        }
    }

    private String getHelpText() {
        return "=== MBean Command Help ===\n" +
               "Inspect and interact with JMX MBeans (Managed Beans)\n\n" +
               "Usage:\n" +
               "  mbean <subcommand> [options]\n" +
               "  mbean --help                     Show this help message\n\n" +
               "Subcommands:\n" +
               "  list [pattern]                   List MBeans (default: all)\n" +
               "  info <ObjectName>                Show detailed MBean information\n" +
               "  get <ObjectName> <Attribute>     Get MBean attribute value\n" +
               "  set <ObjectName> <Attribute> <Value> Set MBean attribute value\n" +
               "  invoke <ObjectName> <Operation> [params...] Invoke MBean operation\n" +
               "  domains                          List all MBean domains\n" +
               "  search <pattern>                 Search MBeans by name or class\n\n" +
               "Examples:\n" +
               "  mbean list                       List all MBeans\n" +
               "  mbean list java.lang:*           List MBeans in java.lang domain\n" +
               "  mbean info java.lang:type=Memory Show Memory MBean details\n" +
               "  mbean get java.lang:type=Memory HeapMemoryUsage Get heap memory info\n" +
               "  mbean invoke java.lang:type=Memory gc Trigger garbage collection\n" +
               "  mbean domains                    Show all domains\n" +
               "  mbean search Memory              Find MBeans containing 'Memory'\n\n" +
               "ObjectName Format:\n" +
               "- domain:key1=value1,key2=value2\n" +
               "- Examples: java.lang:type=Memory, java.util.logging:type=Logging\n" +
               "- Use wildcards: java.lang:*, *:type=Memory\n\n" +
               "Common MBeans:\n" +
               "- java.lang:type=Memory - Memory management\n" +
               "- java.lang:type=Runtime - Runtime information\n" +
               "- java.lang:type=Threading - Thread management\n" +
               "- java.lang:type=ClassLoading - Class loading statistics\n" +
               "- java.lang:type=GarbageCollector,name=* - GC information\n" +
               "- java.lang:type=MemoryPool,name=* - Memory pool details\n\n" +
               "Data Types:\n" +
               "- Simple: String, int, boolean, long, double\n" +
               "- Complex: CompositeData, TabularData, Arrays\n" +
               "- Values are automatically formatted for readability\n\n" +
               "Use Cases:\n" +
               "- Monitor JVM health and performance\n" +
               "- Trigger operations like GC\n" +
               "- Inspect memory and thread usage\n" +
               "- Configure logging levels\n" +
               "- Debug application state\n";
    }

    @Override
    public String getDescription() {
        return "Inspect and interact with JMX MBeans";
    }
}
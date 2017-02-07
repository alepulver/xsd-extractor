import groovy.util.slurpersupport.NodeChild


interface Type {

}

interface Visitor {
    def caseSimpleType(isReadable)
    def caseComplexType(name, attributes, children)
    def caseAttribute()
    def caseElement()
}


class Namespace {
    static String namespace

}

class SimpleType implements Type {
    String name
    Boolean isReadable
    String[] values

    String toString() {
        "SimpleType(${name}, ${isReadable}, ${values})"
    }
}

class ComplexType implements Type {
    String name
    def attributes
    def children

    String toString() {
        "ComplexType(${name}, ${attributes}, ${children})"
    }
}

class Attribute implements Type {
    String name
    def type

    String toString() {
        "Attribute(${name}, ${type})"
    }
}

class Group implements Type {
    String name
    def type
    def values

    String toString() {
        "Group(${name}, ${type}, ${values})"
    }
}


class Element {
    String namespace = Namespace.namespace
    String name
    Boolean isAbstract
    def type

    String toString() {
        "Element(${name}, ${isAbstract}, ${type})"
    }
}

class Reference implements Type {
    def name
    def type

    String toString() {
        "Reference(${name}, ${type})"
    }
}

// use extension to inherit, and restriction as equal, choice as sequence, etc

class SchemaLoader {
    def elements
    def types
    def aliases

    SchemaLoader() {
        elements = [:]
        types = [:]
        aliases = [:]
    }

    void processDir(File dir) {

    }

    void process(File f) {
        def rootNode = new XmlSlurper().parse(f)
        def fileNameList = f.getName().split("-")
        if (fileNameList.size() > 1) {
            Namespace.namespace = fileNameList[1].split("\\.")[0]
        } else {
            Namespace.namespace = fileNameList[0].split("\\.")[0]
        }

        println Namespace.namespace

        rootNode.children().each { node ->
            def name = node.name()
            if (name == "import" || name == "include" || name == "annotation") {
                // ignore
            } else if (name == "element") {
                def e = parseElement(node)
                elements.put(node.@name.toString(), e)
            } else if (name == "simpleType" || name == "complexType" || name == "group" || name == "attributeGroup") {
                parseType(node)
            } else {
                println "unknown tag ${name}"
            }
        }
    }

    def parseType(NodeChild node) {
        def name = node.name()
        def currentType
        if (name == "complexType") {
            // TODO: check if child is complexContent -> restriction/extension of an interesting type

            def attributes = []
            def attributeGroups = []
            def children = []

            def first = node.breadthFirst().find { x ->
                x.name() == "attribute" || x.name() == "attributeGroup" || x.name() == "element"
            }
            if (first?.name() == "attribute" || first?.name() == "attributeGroup") {
                attributes = first.parent().attribute.collect {x -> parseAttribute(x) }
                attributeGroups = first.parent().attributeGroup.collect {x -> parseAttributeGroup(x) }

                first = node.breadthFirst().find { x -> x.name() == "element" }
            }
            if (first?.name() == "element") {
               children = first.parent().element.collect {x ->
                    parseElement(x)
                }
            }
            currentType = new ComplexType(name: node.@name, attributes: (attributes + attributeGroups), children: children)

        } else if (name == "simpleType") {
            // TODO: check if child is restriction/extension of an interesting type

            def anEnumeration = node.breadthFirst().find {x -> x.name() == "enumeration"}
            def values = []
            if (anEnumeration !=null) {
                values = anEnumeration.parent().'*'.findAll { x -> x.name() == "enumeration" }.collect { x -> x.@value }
            }
            currentType = new SimpleType(name: node.@name, isReadable: anEnumeration != null, values: values)

        } else if (name == "group") {
            currentType = parseElementGroup(node)

        } else if (name == "attributeGroup") {
            currentType = parseAttributeGroup(node)

        } else {
            return
        }

        if (!node.@name.isEmpty())
            types.put(node.@name.toString(), currentType)
        currentType
    }

    def parseElement(NodeChild node) {
        if (!node.@substitutionGroup.isEmpty()) {
            assert !node.@name.isEmpty()
            def keyOne = node.@name.toString()
            def keyTwo = node.@substitutionGroup.toString()

            if (aliases.get(keyOne) == null && aliases.get(keyTwo) != null) {
                def set = aliases.get(keyTwo)
                set << keyOne
                aliases.put(keyOne, set)
            } else if (aliases.get(keyOne) != null && aliases.get(keyTwo) == null) {
                def set = aliases.get(keyOne)
                set << keyTwo
                aliases.put(keyTwo, set)
            } else {
                def set = [keyOne, keyTwo]
                aliases.put(keyOne, set)
                aliases.put(keyTwo, set)
            }
        }

        if (!node.@type.isEmpty()) {
            new Element(name: node.@name, isAbstract: (node.@abstract == "true"), type: node.@type.toString())
        } else if (!node.@ref.isEmpty()) {
            return new Reference(name: node.@ref, type: "element")
        } else {
            def inlineType = node.breadthFirst().find { x -> x.name() == "simpleType" || x.name() == "complexType"}
            if (inlineType != null) {
                def elementType = parseType(inlineType)
                return new Element(name: node.@name, type: elementType)
            } else {
                println "ignoring case for ${node.name()} -> ${node.@name} (${node.attributes()})"
            }
        }
    }

    def parseAttribute(NodeChild node) {
        if (!node.@type.isEmpty()) {
            new Attribute(name: node.@name, type: node.@type)
        } else {
            def inlineType = node.breadthFirst().find { x -> x.name() == "simpleType"}
            if (inlineType != null) {
                def elementType = parseType(inlineType)
                new Attribute(name: node.@name, type: elementType)
            } else {
                println "ignoring case for ${node.name()} -> ${node.@name} (${node.attributes()})"
            }
        }
    }

    def parseElementGroup(NodeChild node) {
        if (!node.@name.isEmpty()) {
            def values = node."**".findAll {x -> x.name() == "element"}.collect {x -> parseElement(x)}
            def result = new Group(name: node.@name, type: "elements", values: values)
            result
        } else if (!node.@ref.isEmpty()) {
            new Reference(name: node.@ref, type: "elementGroup")
        } else {
            println "ignoring inline type for ${node.name()} -> ${node.@name}"
        }
    }

    def parseAttributeGroup(NodeChild node) {
        if (!node.@name.isEmpty()) {
            def values = node."**".findAll {x -> x.name() == "attribute"}.collect {x -> parseAttribute(x) }
            def result = new Group(name: node.@name, type: "attributes", values: values)
            result
        } else if (!node.@ref.isEmpty()) {
            new Reference(name: node.@ref, type: "attributeGroup")
        } else {
            println "ignoring inline type for ${node.name()} -> ${node.@name}"
        }
    }

    def generateSamples() {
        elements.collectMany { key, value ->
            if (value != null && !value.getIsAbstract()) {
                samplesFor(value, [key], false)
            } else {
                []
            }
        }
    }

    def samplesFor(entity, path, didAlias) {
        if (path.subList(0, path.size()-1).contains(path[path.size()-1])) {
            return []
        }

        if (entity instanceof Element) {
            def group = aliases.get(entity.getName().toString())
            if (group != null && !didAlias) {
                return group.collectMany { x ->
                    def e = elements.get(x.toString())
                    if (e != null) {
                        return samplesFor(e, path, true)
                    } else {
                        //println "unknown element ${x}"
                        return []
                    }
                }
//                def entityType = types.get(entity.getType().toString())
//                if (entityType != null) {
//                    samplesFor(entityType, path + [group], false)
//                }
            } else if (entity.getType() instanceof String) {
                def typeName = entity.getType()
                if (typeName.contains(":")) {
                    typeName = typeName.split(":")[1]
                }
                def entityType = types.get(typeName)
                if (entityType != null) {
                    return samplesFor(entityType, path + [entity.namespace + ":" + entity.getName()], false)
                } else {
                    println "unknown type: ${typeName} for ${entity}"
                }
            } else {
                return samplesFor(entity.getType(), path + [entity.namespace + ":" + entity.getName()], false)
            }

        } else if (entity instanceof Reference) {
            def realEntity
            if (entity.getType() == "element") {
                realEntity = elements.get(entity.getName().toString())
            } else {
                realEntity = types.get(entity.getName().toString())
            }

            if (realEntity != null) {
                return samplesFor(realEntity, path, didAlias)
            } else {
                println "unknown reference ${entity.getName()}"
            }

        } else if (entity instanceof Attribute) {
            def entityType = types.get(entity.getType().toString())
            if (entityType != null) {
                //return samplesFor(entityType, path + ['@' + entity.getName()], didAlias)
                return samplesFor(entityType, path + [entity.getName()], didAlias)
            } else if (entity.getType() instanceof Type) {
                return samplesFor(entity.getType(), path + [entity.getName()], false)
            } else {
                def typeName = entity.getType().toString().toLowerCase()
                if (typeName.contains("bool") || typeName.contains("enumeration")) {
                    //def result = path + ['@' + entity.getName()]
                    def result = path + [entity.getName()]
                    return [result]
                } else {
                    println "ignoring attribute type ${entity.getType()}"
                }
            }

        } else if (entity instanceof Group) {
            return entity.getValues().collectMany { x -> samplesFor(x, path, didAlias) }

        } else if (entity instanceof SimpleType) {
            if (entity.getIsReadable()) {
                //def result = path + [entity.getValues()]
                def result = path
                return [result]
            }

        } else if (entity instanceof ComplexType) {
            def results = []
            results.addAll(entity.getAttributes().collectMany { x -> samplesFor(x, path, didAlias) })
            results.addAll(entity.getChildren().collectMany { x -> samplesFor(x, path, didAlias) })
            return results

        } else {
            println "unknown thing ${entity}, in ${path}"
        }

        return []
    }
}

class Main {

    static void main(String[] args) {
        //print new File("src/resources/xsd-files/mule.xsd")
        //def path = getClass().getResource("/xsd-files/mule.xsd").getFile()

        def loader = new SchemaLoader()
        def files = new File(getClass().getResource("/xsd-files").getFile()).listFiles()
        for (f in files) {
            println f.getPath()
            loader.process(f)
        }

//        println loader.getElements().size()
//        println loader.getElements()
//        println loader.getAliases()
        def samples = loader.generateSamples()
        println loader.getTypes()
        samples = samples.collect { x -> x.subList(x.size()-2, x.size()) }.unique(false)
        samples.each { x -> println x }
    }
}

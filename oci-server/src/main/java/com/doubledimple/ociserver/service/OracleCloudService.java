package com.doubledimple.ociserver.service;

import com.doubledimple.ociserver.config.MultiUserAuthenticationDetailsProvider;
import com.doubledimple.ociserver.config.OracleUsersConfig;
import com.doubledimple.ociserver.domain.User;
import com.doubledimple.ociserver.enums.ArchitectureEnum;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.ComputeWaiters;
import com.oracle.bmc.core.VirtualNetworkClient;

import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.core.responses.*;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.responses.ListAvailabilityDomainsResponse;
import com.oracle.bmc.workrequests.WorkRequestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * @author doubleDimple
 * @date 2024:09:21日 08:47
 */
@Service
@Slf4j
public class OracleCloudService {

    private static final String OPERATING_SYSTEM = "Oracle Linux";
    private final OracleUsersConfig oracleUsersConfig;

    @Autowired
    private MultiUserAuthenticationDetailsProvider multiUserAuthenticationDetailsProvider;

    private final Map<String,Long> count = new ConcurrentHashMap<>();

    @Autowired
    public OracleCloudService(OracleUsersConfig oracleUsersConfig) {
        this.oracleUsersConfig = oracleUsersConfig;
    }

    public boolean createInstanceData(User user) throws Exception {
        Long aLong = 0L;
        if (count.containsKey(user.getUserName())){
            aLong = count.get(user.getUserName());
            aLong +=1;
            count.put(user.getUserName(),aLong);
        }else{
            aLong +=1;
            count.put(user.getUserName(),aLong);
        }

        log.info("用户:[{}] 开始执行第[{}]次创建实例操作......",user.getUserName(),aLong);

        Map<String, SimpleAuthenticationDetailsProvider> providerMap = multiUserAuthenticationDetailsProvider.simpleAuthenticationDetailsProvider();

        SimpleAuthenticationDetailsProvider authenticationDetailsProvider = providerMap.get(user.getUserId());
        String compartmentId = authenticationDetailsProvider.getTenantId();
        IdentityClient identityClient =
                IdentityClient.builder().build(authenticationDetailsProvider);
        identityClient.setRegion(user.getRegion());
        ComputeClient computeClient = ComputeClient.builder().build(authenticationDetailsProvider);
        computeClient.setRegion(user.getRegion());
        WorkRequestClient workRequestClient =
                WorkRequestClient.builder().build(authenticationDetailsProvider);
        workRequestClient.setRegion(user.getRegion());
        ComputeWaiters computeWaiters = computeClient.newWaiters(workRequestClient);

        VirtualNetworkClient virtualNetworkClient =
                VirtualNetworkClient.builder().build(authenticationDetailsProvider);
        virtualNetworkClient.setRegion(user.getRegion());

        BlockstorageClient blockstorageClient =
                BlockstorageClient.builder().build(authenticationDetailsProvider);
        blockstorageClient.setRegion(user.getRegion());

        AvailabilityDomain availablityDomain =
                getAvailabilityDomains(identityClient, compartmentId);
        log.info("<==================Start get Shape==================>");
        Shape shape = getShape(computeClient, compartmentId, availablityDomain,user);
        Image image = getImage(computeClient, compartmentId, shape);
        String networkCidrBlock = getCidr(virtualNetworkClient,compartmentId);

        String kmsKeyId = null;
        Vcn vcn = null;
        InternetGateway internetGateway = null;
        Subnet subnet = null;
        NetworkSecurityGroup networkSecurityGroup = null;
        LaunchInstanceDetails launchInstanceDetails = null;
        Instance instance = null;
        Instance instanceFromBootVolume = null;
        BootVolume bootVolume = null;
        try {
            vcn = createVcn(virtualNetworkClient, compartmentId, networkCidrBlock);

            internetGateway = createInternetGateway(virtualNetworkClient, compartmentId, vcn);
            addInternetGatewayToDefaultRouteTable(virtualNetworkClient, vcn, internetGateway);

            subnet = createSubnet(virtualNetworkClient, compartmentId, availablityDomain, networkCidrBlock, vcn);
            networkSecurityGroup =
                    createNetworkSecurityGroup(virtualNetworkClient, compartmentId, vcn);
            addNetworkSecurityGroupSecurityRules(
                    virtualNetworkClient, networkSecurityGroup, networkCidrBlock);

            log.info("current user:[{}] and region:[{}] Instance is being created via image and KMS key ...",user.getUserName(),user.getRegion());
            String rootPassword = user.getRootPassword();
            // 准备 cloud-init 脚本
            String cloudInitScript =
                    "#cloud-config\n" +
                            "ssh_pwauth: yes\n" +
                            "chpasswd:\n" +
                            "  list: |\n" +
                            "    root:"+rootPassword +
                            "  expire: false\n" +
                            "runcmd:\n" +
                            "  - sed -i 's/PasswordAuthentication no/PasswordAuthentication yes/' /etc/ssh/sshd_config\n" +
                            "  - sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config\n" +
                            "  - systemctl restart sshd";
            launchInstanceDetails =
                    createLaunchInstanceDetails(
                            compartmentId,
                            availablityDomain,
                            shape,
                            image,
                            subnet,
                            networkSecurityGroup,
                            cloudInitScript);
            instance = createInstance(computeWaiters, launchInstanceDetails);
            printInstance(computeClient, virtualNetworkClient, instance);

            log.info("Current User:[{}] and Region:[{}] Instance is being created via boot volume ...",user.getUserName(),user.getRegion());
            log.info("<================================================>");
            bootVolume =
                    createBootVolume(
                            blockstorageClient, compartmentId, availablityDomain, image, kmsKeyId);
            launchInstanceDetails =
                    createLaunchInstanceDetailsFromBootVolume(launchInstanceDetails, bootVolume);
            instanceFromBootVolume = createInstance(computeWaiters, launchInstanceDetails);
            printInstance(computeClient, virtualNetworkClient, instanceFromBootVolume);

        } finally {
            if (instanceFromBootVolume != null) {
                terminateInstance(computeClient, instanceFromBootVolume);
            }
            if (instance != null) {
                terminateInstance(computeClient, instance);
            }
            if (networkSecurityGroup != null) {
                clearNetworkSecurityGroupSecurityRules(virtualNetworkClient, networkSecurityGroup);
                deleteNetworkSecurityGroup(virtualNetworkClient, networkSecurityGroup);
            }
            if (internetGateway != null) {
                clearRouteRulesFromDefaultRouteTable(virtualNetworkClient, vcn);
                deleteInternetGateway(virtualNetworkClient, internetGateway);
            }
            if (subnet != null) {
                deleteSubnet(virtualNetworkClient, subnet);
            }
            if (vcn != null) {
                deleteVcn(virtualNetworkClient, vcn);
            }

            identityClient.close();
            computeClient.close();
            workRequestClient.close();
            virtualNetworkClient.close();
            blockstorageClient.close();
        }
        return true;
    }

    private String getCidr(VirtualNetworkClient virtualNetworkClient, String compartmentId) {
        // 创建列出 VCN 的请求
        ListVcnsRequest listVcnsRequest = ListVcnsRequest.builder()
                .compartmentId(compartmentId)
                .build();

        // 发送请求并获取响应
        ListVcnsResponse listVcnsResponse = virtualNetworkClient.listVcns(listVcnsRequest);

        // 遍历所有 VCN 并打印其 CIDR 块
        for (Vcn vcn : listVcnsResponse.getItems()) {
            log.info("VCN Name: " + vcn.getDisplayName());
            log.info("VCN ID: " + vcn.getId());
            log.info("CIDR Block: " + vcn.getCidrBlock());

            // 如果 VCN 有多个 CIDR 块，也打印出来
            List<String> cidrBlocks = vcn.getCidrBlocks();
            if (cidrBlocks != null && !cidrBlocks.isEmpty()) {
                System.out.println("Additional CIDR Blocks:");
                for (String cidr : cidrBlocks) {
                    System.out.println("  " + cidr);
                }
            }
            log.info("<================================================>");        }
        return listVcnsResponse.getItems().get(0).getCidrBlock();
    }

    private static AvailabilityDomain getAvailabilityDomains(
            IdentityClient identityClient, String compartmentId) throws Exception {
        ListAvailabilityDomainsResponse listAvailabilityDomainsResponse =
                identityClient.listAvailabilityDomains(
                        ListAvailabilityDomainsRequest.builder()
                                .compartmentId(compartmentId)
                                .build());
        List<AvailabilityDomain> availabilityDomains = listAvailabilityDomainsResponse.getItems();
        // For demonstration, we just return the first availability domain but for Production code
        // you should
        // have a better way of determining what is needed
        AvailabilityDomain availabilityDomain = availabilityDomains.get(0);

        log.info("Found Availability Domain: " + availabilityDomain.getName());
        log.info("<================================>");

        return availabilityDomain;
    }

    private static Shape getShape(
            ComputeClient computeClient,
            String compartmentId,
            AvailabilityDomain availabilityDomain,
            User user) {
        ListShapesRequest listShapesRequest =
                ListShapesRequest.builder()
                        .availabilityDomain(availabilityDomain.getName())
                        .compartmentId(compartmentId)
                        .build();
        ListShapesResponse listShapesResponse = computeClient.listShapes(listShapesRequest);
        List<Shape> shapes = listShapesResponse.getItems();
        if (shapes.isEmpty()) {
            throw new IllegalStateException("No available shape was found.");
        }
        List<Shape> vmShapes =
                shapes.stream()
                        .filter(shape -> shape.getShape().startsWith("VM"))
                        .collect(Collectors.toList());
        if (vmShapes.isEmpty()) {
            throw new IllegalStateException("No available VM shape was found.");
        }
        Shape shape = vmShapes.get(0);

        ArchitectureEnum type = ArchitectureEnum.getType(user.getArchitecture());
        if (type == null){
            type = ArchitectureEnum.ARM;
        }
        for (Shape vmShape : vmShapes) {
            if (vmShape.getShape().equals(type.getShapeDetail())){
                return vmShape;
            }
            log.info("Found Shape: " + vmShape.getShape());
            log.info("Billing Type: " + vmShape.getBillingType());
            log.info("<====================================>");
        }
        return shape;
    }

    private static Image getImage(ComputeClient computeClient, String compartmentId, Shape shape)
            throws Exception {
        ListImagesRequest listImagesRequest =
                ListImagesRequest.builder()
                        .shape(shape.getShape())
                        .compartmentId(compartmentId)
                        .operatingSystem(OPERATING_SYSTEM)
                        .build();
        ListImagesResponse response = computeClient.listImages(listImagesRequest);
        List<Image> images = response.getItems();
        if (images.isEmpty()) {
            throw new IllegalStateException("No available image was found.");
        }

        // For demonstration, we just return the first image but for Production code you should have
        // a better
        // way of determining what is needed.
        //
        // Note the latest version of the images for the same operating system is returned firstly.
        Image image = images.get(0);

        System.out.println("Found Image: " + image.getDisplayName());
        System.out.println();

        return image;
    }

    private static Vcn createVcn(
            VirtualNetworkClient virtualNetworkClient, String compartmentId, String cidrBlock)
            throws Exception {
        String vcnName = "java-sdk-example-vcn";
        //先查询是否存在,不存在在创建,存在直接返回
        ListVcnsRequest build = ListVcnsRequest.builder().compartmentId(compartmentId)
                .displayName(vcnName)
                .build();

        ListVcnsResponse listVcnsResponse = virtualNetworkClient.listVcns(build);
        if (listVcnsResponse.getItems().size() > 0){
            return listVcnsResponse.getItems().get(0);
        }
        CreateVcnDetails createVcnDetails =
                CreateVcnDetails.builder()
                        .cidrBlock(cidrBlock)
                        .compartmentId(compartmentId)
                        .displayName(vcnName)
                        .build();

        CreateVcnRequest createVcnRequest =
                CreateVcnRequest.builder().createVcnDetails(createVcnDetails).build();
        CreateVcnResponse createVcnResponse = virtualNetworkClient.createVcn(createVcnRequest);

        GetVcnRequest getVcnRequest =
                GetVcnRequest.builder().vcnId(createVcnResponse.getVcn().getId()).build();
        GetVcnResponse getVcnResponse =
                virtualNetworkClient
                        .getWaiters()
                        .forVcn(getVcnRequest, Vcn.LifecycleState.Available)
                        .execute();
        Vcn vcn = getVcnResponse.getVcn();

        System.out.println("Created Vcn: " + vcn.getId());
        System.out.println(vcn);
        System.out.println();

        return vcn;
    }

    private static void deleteVcn(VirtualNetworkClient virtualNetworkClient, Vcn vcn)
            throws Exception {
        DeleteVcnRequest deleteVcnRequest = DeleteVcnRequest.builder().vcnId(vcn.getId()).build();
        virtualNetworkClient.deleteVcn(deleteVcnRequest);

        GetVcnRequest getVcnRequest = GetVcnRequest.builder().vcnId(vcn.getId()).build();
        virtualNetworkClient
                .getWaiters()
                .forVcn(getVcnRequest, Vcn.LifecycleState.Terminated)
                .execute();

        System.out.println("Deleted Vcn: " + vcn.getId());
        System.out.println();
    }

    private static InternetGateway createInternetGateway(
            VirtualNetworkClient virtualNetworkClient, String compartmentId, Vcn vcn)
            throws Exception {
        String internetGatewayName = "java-sdk-example-internet-gateway";

        //查询网关是否存在,不存在再创建
        ListInternetGatewaysRequest build = ListInternetGatewaysRequest.builder()
                .compartmentId(compartmentId)
                .displayName(internetGatewayName).build();

        ListInternetGatewaysResponse listInternetGatewaysResponse = virtualNetworkClient.listInternetGateways(build);
        if (listInternetGatewaysResponse.getItems().size() > 0){
            return listInternetGatewaysResponse.getItems().get(0);
        }

        CreateInternetGatewayDetails createInternetGatewayDetails =
                CreateInternetGatewayDetails.builder()
                        .compartmentId(compartmentId)
                        .displayName(internetGatewayName)
                        .isEnabled(true)
                        .vcnId(vcn.getId())
                        .build();
        CreateInternetGatewayRequest createInternetGatewayRequest =
                CreateInternetGatewayRequest.builder()
                        .createInternetGatewayDetails(createInternetGatewayDetails)
                        .build();
        CreateInternetGatewayResponse createInternetGatewayResponse =
                virtualNetworkClient.createInternetGateway(createInternetGatewayRequest);

        GetInternetGatewayRequest getInternetGatewayRequest =
                GetInternetGatewayRequest.builder()
                        .igId(createInternetGatewayResponse.getInternetGateway().getId())
                        .build();
        GetInternetGatewayResponse getInternetGatewayResponse =
                virtualNetworkClient
                        .getWaiters()
                        .forInternetGateway(
                                getInternetGatewayRequest, InternetGateway.LifecycleState.Available)
                        .execute();
        InternetGateway internetGateway = getInternetGatewayResponse.getInternetGateway();

        System.out.println("Created Internet Gateway: " + internetGateway.getId());
        System.out.println(internetGateway);
        System.out.println();

        return internetGateway;
    }

    private static void deleteInternetGateway(
            VirtualNetworkClient virtualNetworkClient, InternetGateway internetGateway)
            throws Exception {
        DeleteInternetGatewayRequest deleteInternetGatewayRequest =
                DeleteInternetGatewayRequest.builder().igId(internetGateway.getId()).build();
        virtualNetworkClient.deleteInternetGateway(deleteInternetGatewayRequest);

        GetInternetGatewayRequest getInternetGatewayRequest =
                GetInternetGatewayRequest.builder().igId(internetGateway.getId()).build();
        virtualNetworkClient
                .getWaiters()
                .forInternetGateway(
                        getInternetGatewayRequest, InternetGateway.LifecycleState.Terminated)
                .execute();

        System.out.println("Deleted Internet Gateway: " + internetGateway.getId());
        System.out.println();
    }

    private static void addInternetGatewayToDefaultRouteTable(
            VirtualNetworkClient virtualNetworkClient, Vcn vcn, InternetGateway internetGateway)
            throws Exception {
        GetRouteTableRequest getRouteTableRequest =
                GetRouteTableRequest.builder().rtId(vcn.getDefaultRouteTableId()).build();
        GetRouteTableResponse getRouteTableResponse =
                virtualNetworkClient.getRouteTable(getRouteTableRequest);
        List<RouteRule> routeRules = getRouteTableResponse.getRouteTable().getRouteRules();

        System.out.println("Current Route Rules in Default Route Table");
        System.out.println("==========================================");
        routeRules.forEach(System.out::println);
        System.out.println();

        RouteRule internetAccessRoute =
                RouteRule.builder()
                        .destination("0.0.0.0/0")
                        .destinationType(RouteRule.DestinationType.CidrBlock)
                        .networkEntityId(internetGateway.getId())
                        .build();
        routeRules.add(internetAccessRoute);
        UpdateRouteTableDetails updateRouteTableDetails =
                UpdateRouteTableDetails.builder().routeRules(routeRules).build();
        UpdateRouteTableRequest updateRouteTableRequest =
                UpdateRouteTableRequest.builder()
                        .updateRouteTableDetails(updateRouteTableDetails)
                        .rtId(vcn.getDefaultRouteTableId())
                        .build();
        virtualNetworkClient.updateRouteTable(updateRouteTableRequest);

        getRouteTableResponse =
                virtualNetworkClient
                        .getWaiters()
                        .forRouteTable(getRouteTableRequest, RouteTable.LifecycleState.Available)
                        .execute();
        routeRules = getRouteTableResponse.getRouteTable().getRouteRules();

        System.out.println("Updated Route Rules in Default Route Table");
        System.out.println("==========================================");
        routeRules.forEach(System.out::println);
        System.out.println();
    }

    private static void clearRouteRulesFromDefaultRouteTable(
            VirtualNetworkClient virtualNetworkClient, Vcn vcn) throws Exception {
        List<RouteRule> routeRules = new ArrayList<>();
        UpdateRouteTableDetails updateRouteTableDetails =
                UpdateRouteTableDetails.builder().routeRules(routeRules).build();
        UpdateRouteTableRequest updateRouteTableRequest =
                UpdateRouteTableRequest.builder()
                        .updateRouteTableDetails(updateRouteTableDetails)
                        .rtId(vcn.getDefaultRouteTableId())
                        .build();
        virtualNetworkClient.updateRouteTable(updateRouteTableRequest);

        GetRouteTableRequest getRouteTableRequest =
                GetRouteTableRequest.builder().rtId(vcn.getDefaultRouteTableId()).build();
        virtualNetworkClient
                .getWaiters()
                .forRouteTable(getRouteTableRequest, RouteTable.LifecycleState.Available)
                .execute();

        System.out.println("Cleared route rules from route table: " + vcn.getDefaultRouteTableId());
        System.out.println();
    }

    private static Subnet createSubnet(
            VirtualNetworkClient virtualNetworkClient,
            String compartmentId,
            AvailabilityDomain availabilityDomain,
            String networkCidrBlock,
            Vcn vcn)
            throws Exception {
        String subnetName = "java-sdk-example-subnet";

        //检查子网是否存在
        ListSubnetsRequest listRequest = ListSubnetsRequest.builder()
                .compartmentId(compartmentId)
                .vcnId(vcn.getId())
                .displayName(subnetName)
                .build();
        ListSubnetsResponse listResponse = virtualNetworkClient.listSubnets(listRequest);
        if (listResponse.getItems().size() > 0){
            // 如果找到已有的子网，返回其 ID
            for (Subnet subnet : listResponse.getItems()) {
                if (subnet.getDisplayName().equals(subnetName)) {
                    return subnet; // 返回已存在子网的 ID
                }
            }
        }


        // In order to launch instances in a regional subnet, build this CreateSubnetDetails with
        // the field availablityDomain set to null.
        CreateSubnetDetails createSubnetDetails =
                CreateSubnetDetails.builder()
                        .availabilityDomain(availabilityDomain.getName())
                        .compartmentId(compartmentId)
                        .displayName(subnetName)
                        .cidrBlock(networkCidrBlock)
                        .vcnId(vcn.getId())
                        .routeTableId(vcn.getDefaultRouteTableId())
                        .build();
        CreateSubnetRequest createSubnetRequest =
                CreateSubnetRequest.builder().createSubnetDetails(createSubnetDetails).build();
        CreateSubnetResponse createSubnetResponse =
                virtualNetworkClient.createSubnet(createSubnetRequest);

        GetSubnetRequest getSubnetRequest =
                GetSubnetRequest.builder()
                        .subnetId(createSubnetResponse.getSubnet().getId())
                        .build();
        GetSubnetResponse getSubnetResponse =
                virtualNetworkClient
                        .getWaiters()
                        .forSubnet(getSubnetRequest, Subnet.LifecycleState.Available)
                        .execute();
        Subnet subnet = getSubnetResponse.getSubnet();

        System.out.println("Created Subnet: " + subnet.getId());
        System.out.println(subnet);
        System.out.println();

        return subnet;
    }

    private static void deleteSubnet(VirtualNetworkClient virtualNetworkClient, Subnet subnet)
            throws Exception {
        DeleteSubnetRequest deleteSubnetRequest =
                DeleteSubnetRequest.builder().subnetId(subnet.getId()).build();
        virtualNetworkClient.deleteSubnet(deleteSubnetRequest);

        GetSubnetRequest getSubnetRequest =
                GetSubnetRequest.builder().subnetId(subnet.getId()).build();
        virtualNetworkClient
                .getWaiters()
                .forSubnet(getSubnetRequest, Subnet.LifecycleState.Terminated)
                .execute();

        System.out.println("Deleted Subnet: " + subnet.getId());
        System.out.println();
    }

    private static NetworkSecurityGroup createNetworkSecurityGroup(
            VirtualNetworkClient virtualNetworkClient, String compartmentId, Vcn vcn)
            throws Exception {
        String networkSecurityGroupName = "java-sdk-example-nsg";

        CreateNetworkSecurityGroupDetails createNetworkSecurityGroupDetails =
                CreateNetworkSecurityGroupDetails.builder()
                        .compartmentId(compartmentId)
                        .displayName(networkSecurityGroupName)
                        .vcnId(vcn.getId())
                        .build();
        CreateNetworkSecurityGroupRequest createNetworkSecurityGroupRequest =
                CreateNetworkSecurityGroupRequest.builder()
                        .createNetworkSecurityGroupDetails(createNetworkSecurityGroupDetails)
                        .build();

        ListNetworkSecurityGroupsRequest build = ListNetworkSecurityGroupsRequest.builder().
                compartmentId(compartmentId).
                displayName(networkSecurityGroupName).vcnId(vcn.getId()).build();

        ListNetworkSecurityGroupsResponse listNetworkSecurityGroupsResponse = virtualNetworkClient.listNetworkSecurityGroups(build);
        if (listNetworkSecurityGroupsResponse.getItems().size() > 0){
            return listNetworkSecurityGroupsResponse.getItems().get(0);
        }

        CreateNetworkSecurityGroupResponse createNetworkSecurityGroupResponse =
                virtualNetworkClient.createNetworkSecurityGroup(createNetworkSecurityGroupRequest);

        GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest =
                GetNetworkSecurityGroupRequest.builder()
                        .networkSecurityGroupId(
                                createNetworkSecurityGroupResponse
                                        .getNetworkSecurityGroup()
                                        .getId())
                        .build();
        GetNetworkSecurityGroupResponse getNetworkSecurityGroupResponse =
                virtualNetworkClient
                        .getWaiters()
                        .forNetworkSecurityGroup(
                                getNetworkSecurityGroupRequest,
                                NetworkSecurityGroup.LifecycleState.Available)
                        .execute();
        NetworkSecurityGroup networkSecurityGroup =
                getNetworkSecurityGroupResponse.getNetworkSecurityGroup();

        System.out.println("Created Network Security Group: " + networkSecurityGroup.getId());
        System.out.println(networkSecurityGroup);
        System.out.println();

        return networkSecurityGroup;
    }

    private static void deleteNetworkSecurityGroup(
            VirtualNetworkClient virtualNetworkClient, NetworkSecurityGroup networkSecurityGroup)
            throws Exception {
        DeleteNetworkSecurityGroupRequest deleteNetworkSecurityGroupRequest =
                DeleteNetworkSecurityGroupRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .build();
        virtualNetworkClient.deleteNetworkSecurityGroup(deleteNetworkSecurityGroupRequest);

        GetNetworkSecurityGroupRequest getNetworkSecurityGroupRequest =
                GetNetworkSecurityGroupRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .build();
        virtualNetworkClient
                .getWaiters()
                .forNetworkSecurityGroup(
                        getNetworkSecurityGroupRequest,
                        NetworkSecurityGroup.LifecycleState.Terminated)
                .execute();

        System.out.println("Deleted Network Security Group: " + networkSecurityGroup.getId());
        System.out.println();
    }

    private static void addNetworkSecurityGroupSecurityRules(
            VirtualNetworkClient virtualNetworkClient,
            NetworkSecurityGroup networkSecurityGroup,
            String networkCidrBlock) {
        ListNetworkSecurityGroupSecurityRulesRequest listNetworkSecurityGroupSecurityRulesRequest =
                ListNetworkSecurityGroupSecurityRulesRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .build();
        ListNetworkSecurityGroupSecurityRulesResponse
                listNetworkSecurityGroupSecurityRulesResponse =
                virtualNetworkClient.listNetworkSecurityGroupSecurityRules(
                        listNetworkSecurityGroupSecurityRulesRequest);
        List<SecurityRule> securityRules = listNetworkSecurityGroupSecurityRulesResponse.getItems();

        System.out.println("Current Security Rules in Network Security Group");
        System.out.println("================================================");
        securityRules.forEach(System.out::println);
        System.out.println();

        AddSecurityRuleDetails addSecurityRuleDetails =
                AddSecurityRuleDetails.builder()
                        .description("Incoming HTTP connections")
                        .direction(AddSecurityRuleDetails.Direction.Ingress)
                        .protocol("6")
                        .source(networkCidrBlock)
                        .sourceType(AddSecurityRuleDetails.SourceType.CidrBlock)
                        .tcpOptions(
                                TcpOptions.builder()
                                        .destinationPortRange(
                                                PortRange.builder().min(80).max(80).build())
                                        .build())
                        .build();
        AddNetworkSecurityGroupSecurityRulesDetails addNetworkSecurityGroupSecurityRulesDetails =
                AddNetworkSecurityGroupSecurityRulesDetails.builder()
                        .securityRules(Arrays.asList(addSecurityRuleDetails))
                        .build();
        AddNetworkSecurityGroupSecurityRulesRequest addNetworkSecurityGroupSecurityRulesRequest =
                AddNetworkSecurityGroupSecurityRulesRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .addNetworkSecurityGroupSecurityRulesDetails(
                                addNetworkSecurityGroupSecurityRulesDetails)
                        .build();
        virtualNetworkClient.addNetworkSecurityGroupSecurityRules(
                addNetworkSecurityGroupSecurityRulesRequest);

        listNetworkSecurityGroupSecurityRulesResponse =
                virtualNetworkClient.listNetworkSecurityGroupSecurityRules(
                        listNetworkSecurityGroupSecurityRulesRequest);
        securityRules = listNetworkSecurityGroupSecurityRulesResponse.getItems();

        System.out.println("Updated Security Rules in Network Security Group");
        System.out.println("================================================");
        securityRules.forEach(System.out::println);
        System.out.println();
    }

    private static void clearNetworkSecurityGroupSecurityRules(
            VirtualNetworkClient virtualNetworkClient, NetworkSecurityGroup networkSecurityGroup) {
        ListNetworkSecurityGroupSecurityRulesRequest listNetworkSecurityGroupSecurityRulesRequest =
                ListNetworkSecurityGroupSecurityRulesRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .build();
        ListNetworkSecurityGroupSecurityRulesResponse
                listNetworkSecurityGroupSecurityRulesResponse =
                virtualNetworkClient.listNetworkSecurityGroupSecurityRules(
                        listNetworkSecurityGroupSecurityRulesRequest);
        List<SecurityRule> securityRules = listNetworkSecurityGroupSecurityRulesResponse.getItems();

        List<String> securityRuleIds =
                securityRules.stream().map(SecurityRule::getId).collect(Collectors.toList());
        RemoveNetworkSecurityGroupSecurityRulesDetails
                removeNetworkSecurityGroupSecurityRulesDetails =
                RemoveNetworkSecurityGroupSecurityRulesDetails.builder()
                        .securityRuleIds(securityRuleIds)
                        .build();
        RemoveNetworkSecurityGroupSecurityRulesRequest
                removeNetworkSecurityGroupSecurityRulesRequest =
                RemoveNetworkSecurityGroupSecurityRulesRequest.builder()
                        .networkSecurityGroupId(networkSecurityGroup.getId())
                        .removeNetworkSecurityGroupSecurityRulesDetails(
                                removeNetworkSecurityGroupSecurityRulesDetails)
                        .build();
        virtualNetworkClient.removeNetworkSecurityGroupSecurityRules(
                removeNetworkSecurityGroupSecurityRulesRequest);

        System.out.println(
                "Removed all Security Rules in Network Security Group: "
                        + networkSecurityGroup.getId());
        System.out.println();
    }

    private static Instance createInstance(
            ComputeWaiters computeWaiters, LaunchInstanceDetails launchInstanceDetails)
            throws Exception {
        LaunchInstanceRequest launchInstanceRequest =
                LaunchInstanceRequest.builder()
                        .launchInstanceDetails(launchInstanceDetails)
                        .build();
        LaunchInstanceResponse launchInstanceResponse =
                computeWaiters.forLaunchInstance(launchInstanceRequest).execute();

        GetInstanceRequest getInstanceRequest =
                GetInstanceRequest.builder()
                        .instanceId(launchInstanceResponse.getInstance().getId())
                        .build();
        GetInstanceResponse getInstanceResponse =
                computeWaiters
                        .forInstance(getInstanceRequest, Instance.LifecycleState.Running)
                        .execute();
        Instance instance = getInstanceResponse.getInstance();

        System.out.println("Launched Instance: " + instance.getId());
        System.out.println(instance);
        System.out.println();

        return instance;
    }

    private static LaunchInstanceDetails createLaunchInstanceDetails(
            String compartmentId,
            AvailabilityDomain availabilityDomain,
            Shape shape,
            Image image,
            Subnet subnet,
            NetworkSecurityGroup networkSecurityGroup,
            String script) {
        String instanceName = "java-sdk-example-instance";
        Map<String, String> metadata = new HashMap<>();
        String encodedCloudInitScript = Base64.getEncoder().encodeToString(script.getBytes());
        metadata.put("user_data",encodedCloudInitScript);
        metadata = Collections.unmodifiableMap(metadata);
        Map<String, Object> extendedMetadata = new HashMap<>();
        extendedMetadata.put(
                "java-sdk-example-extended-metadata-key",
                "java-sdk-example-extended-metadata-value");
        extendedMetadata = Collections.unmodifiableMap(extendedMetadata);
        InstanceSourceViaImageDetails instanceSourceViaImageDetails =
                InstanceSourceViaImageDetails.builder()
                        .imageId(image.getId())
                        //.kmsKeyId((kmsKeyId == null || "".equals(kmsKeyId)) ? null : kmsKeyId)
                        .build();
        CreateVnicDetails createVnicDetails =
                CreateVnicDetails.builder()
                        .subnetId(subnet.getId())
                        .nsgIds(Arrays.asList(networkSecurityGroup.getId()))
                        .build();
        LaunchInstanceAgentConfigDetails launchInstanceAgentConfigDetails =
                LaunchInstanceAgentConfigDetails.builder().isMonitoringDisabled(false).build();
        return LaunchInstanceDetails.builder()
                .availabilityDomain(availabilityDomain.getName())
                .compartmentId(compartmentId)
                .displayName(instanceName)
                // faultDomain is optional parameter
                .faultDomain("FAULT-DOMAIN-1")
                .sourceDetails(instanceSourceViaImageDetails)
                .metadata(metadata)
                .extendedMetadata(extendedMetadata)
                .shape(shape.getShape())
                .createVnicDetails(createVnicDetails)
                // agentConfig is an optional parameter
                .agentConfig(launchInstanceAgentConfigDetails)
                //配置核心和内存
                .shapeConfig(LaunchInstanceShapeConfigDetails.
                        builder().
                        ocpus(1F).
                        memoryInGBs(1F).
                        build())
                //配置磁盘大小
                .sourceDetails(InstanceSourceViaImageDetails.builder()
                        .imageId(image.getId())
                        .bootVolumeSizeInGBs(50L)
                        .build())
                .build();
    }

    private static void terminateInstance(ComputeClient computeClient, Instance instance)
            throws Exception {
        System.out.println("Terminating Instance: " + instance.getId());
        TerminateInstanceRequest terminateInstanceRequest =
                TerminateInstanceRequest.builder().instanceId(instance.getId()).build();
        computeClient.terminateInstance(terminateInstanceRequest);

        GetInstanceRequest getInstanceRequest =
                GetInstanceRequest.builder().instanceId(instance.getId()).build();
        computeClient
                .getWaiters()
                .forInstance(getInstanceRequest, Instance.LifecycleState.Terminated)
                .execute();

        log.info("Terminated Instance: " + instance.getId());
        log.info("<=======================================>");
    }

    private static void printInstance(
            ComputeClient computeClient,
            VirtualNetworkClient virtualNetworkClient,
            Instance instance) {
        ListVnicAttachmentsRequest listVnicAttachmentsRequest =
                ListVnicAttachmentsRequest.builder()
                        .compartmentId(instance.getCompartmentId())
                        .instanceId(instance.getId())
                        .build();
        ListVnicAttachmentsResponse listVnicAttachmentsResponse =
                computeClient.listVnicAttachments(listVnicAttachmentsRequest);
        List<VnicAttachment> vnicAttachments = listVnicAttachmentsResponse.getItems();
        VnicAttachment vnicAttachment = vnicAttachments.get(0);

        GetVnicRequest getVnicRequest =
                GetVnicRequest.builder().vnicId(vnicAttachment.getVnicId()).build();
        GetVnicResponse getVnicResponse = virtualNetworkClient.getVnic(getVnicRequest);
        Vnic vnic = getVnicResponse.getVnic();

        log.info("Virtual Network Interface Card :" + vnic.getId());
        log.info("Public IP :" + vnic.getPublicIp());
        log.info("Private IP :" + vnic.getPrivateIp());
        log.info("vnic: [{}]",vnic);
        log.info("<=======================================>");

        InstanceAgentConfig instanceAgentConfig = instance.getAgentConfig();
        boolean monitoringEnabled =
                (instanceAgentConfig != null) && !instanceAgentConfig.getIsMonitoringDisabled();
        String monitoringStatus = (monitoringEnabled ? "Enabled" : "Disabled");
        log.info("Instance " + instance.getId() + " has monitoring " + monitoringStatus);
        log.info("<=======================================>");
    }

    private static BootVolume createBootVolume(
            BlockstorageClient blockstorageClient,
            String compartmentId,
            AvailabilityDomain availabilityDomain,
            Image image,
            String kmsKeyId)
            throws Exception {
        String bootVolumeName = "java-sdk-example-boot-volume";
        // find existing boot volume by image
        ListBootVolumesRequest listBootVolumesRequest =
                ListBootVolumesRequest.builder()
                        .availabilityDomain(availabilityDomain.getName())
                        .compartmentId(compartmentId)
                        .build();
        ListBootVolumesResponse listBootVolumesResponse =
                blockstorageClient.listBootVolumes(listBootVolumesRequest);
        List<BootVolume> bootVolumes = listBootVolumesResponse.getItems();
        String bootVolumeId = null;
        for (BootVolume bootVolume : bootVolumes) {
            if (BootVolume.LifecycleState.Available.equals(bootVolume.getLifecycleState())
                    && image.getId().equals(bootVolume.getImageId())) {
                bootVolumeId = bootVolume.getId();
                break;
            }
        }
        System.out.println("Found BootVolume: " + bootVolumeId);

        // create a new boot volume based on existing one
        BootVolumeSourceDetails bootVolumeSourceDetails =
                BootVolumeSourceFromBootVolumeDetails.builder().id(bootVolumeId).build();
        CreateBootVolumeDetails details =
                CreateBootVolumeDetails.builder()
                        .availabilityDomain(availabilityDomain.getName())
                        .compartmentId(compartmentId)
                        .displayName(bootVolumeName)
                        .sourceDetails(bootVolumeSourceDetails)
                        .kmsKeyId(kmsKeyId)
                        .build();
        CreateBootVolumeRequest createBootVolumeRequest =
                CreateBootVolumeRequest.builder().createBootVolumeDetails(details).build();
        CreateBootVolumeResponse createBootVolumeResponse =
                blockstorageClient.createBootVolume(createBootVolumeRequest);
        System.out.println(
                "Provisioning new BootVolume: " + createBootVolumeResponse.getBootVolume().getId());

        // wait for boot volume to be ready
        GetBootVolumeRequest getBootVolumeRequest =
                GetBootVolumeRequest.builder()
                        .bootVolumeId(createBootVolumeResponse.getBootVolume().getId())
                        .build();
        GetBootVolumeResponse getBootVolumeResponse =
                blockstorageClient
                        .getWaiters()
                        .forBootVolume(getBootVolumeRequest, BootVolume.LifecycleState.Available)
                        .execute();
        BootVolume bootVolume = getBootVolumeResponse.getBootVolume();

        System.out.println("Provisioned BootVolume: " + bootVolume.getId());
        System.out.println(bootVolume);
        System.out.println();

        return bootVolume;
    }

    private static LaunchInstanceDetails createLaunchInstanceDetailsFromBootVolume(
            LaunchInstanceDetails launchInstanceDetails, BootVolume bootVolume) throws Exception {
        String bootVolumeName = "java-sdk-example-instance-from-boot-volume";
        InstanceSourceViaBootVolumeDetails instanceSourceViaBootVolumeDetails =
                InstanceSourceViaBootVolumeDetails.builder()
                        .bootVolumeId(bootVolume.getId())
                        .build();
        LaunchInstanceAgentConfigDetails launchInstanceAgentConfigDetails =
                LaunchInstanceAgentConfigDetails.builder().isMonitoringDisabled(true).build();
        return LaunchInstanceDetails.builder()
                .copy(launchInstanceDetails)
                .sourceDetails(instanceSourceViaBootVolumeDetails)
                .agentConfig(launchInstanceAgentConfigDetails)
                .build();
    }
}
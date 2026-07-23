package io.browsercloud.proto.node.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
 * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: node/v1/node_command.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class NodeEventServiceGrpc {

  private NodeEventServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "browsercloud.node.v1.NodeEventService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PublishRequest,
      io.browsercloud.proto.node.v1.PublishResponse> getPublishMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Publish",
      requestType = io.browsercloud.proto.node.v1.PublishRequest.class,
      responseType = io.browsercloud.proto.node.v1.PublishResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PublishRequest,
      io.browsercloud.proto.node.v1.PublishResponse> getPublishMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PublishRequest, io.browsercloud.proto.node.v1.PublishResponse> getPublishMethod;
    if ((getPublishMethod = NodeEventServiceGrpc.getPublishMethod) == null) {
      synchronized (NodeEventServiceGrpc.class) {
        if ((getPublishMethod = NodeEventServiceGrpc.getPublishMethod) == null) {
          NodeEventServiceGrpc.getPublishMethod = getPublishMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.PublishRequest, io.browsercloud.proto.node.v1.PublishResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Publish"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PublishRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PublishResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeEventServiceMethodDescriptorSupplier("Publish"))
              .build();
        }
      }
    }
    return getPublishMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NodeEventServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceStub>() {
        @java.lang.Override
        public NodeEventServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeEventServiceStub(channel, callOptions);
        }
      };
    return NodeEventServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NodeEventServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceBlockingStub>() {
        @java.lang.Override
        public NodeEventServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeEventServiceBlockingStub(channel, callOptions);
        }
      };
    return NodeEventServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NodeEventServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeEventServiceFutureStub>() {
        @java.lang.Override
        public NodeEventServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeEventServiceFutureStub(channel, callOptions);
        }
      };
    return NodeEventServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
   * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void publish(io.browsercloud.proto.node.v1.PublishRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PublishResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPublishMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service NodeEventService.
   * <pre>
   * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
   * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
   * </pre>
   */
  public static abstract class NodeEventServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return NodeEventServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service NodeEventService.
   * <pre>
   * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
   * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
   * </pre>
   */
  public static final class NodeEventServiceStub
      extends io.grpc.stub.AbstractAsyncStub<NodeEventServiceStub> {
    private NodeEventServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeEventServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeEventServiceStub(channel, callOptions);
    }

    /**
     */
    public void publish(io.browsercloud.proto.node.v1.PublishRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PublishResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service NodeEventService.
   * <pre>
   * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
   * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
   * </pre>
   */
  public static final class NodeEventServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<NodeEventServiceBlockingStub> {
    private NodeEventServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeEventServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeEventServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.browsercloud.proto.node.v1.PublishResponse publish(io.browsercloud.proto.node.v1.PublishRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPublishMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service NodeEventService.
   * <pre>
   * Browser Node 将命令执行结果作为版本化事件回传给 Control Plane。
   * Control Plane 只有在 Inbox 去重和 Coordinator 状态提交成功后才确认事件。
   * </pre>
   */
  public static final class NodeEventServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<NodeEventServiceFutureStub> {
    private NodeEventServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeEventServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeEventServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.browsercloud.proto.node.v1.PublishResponse> publish(
        io.browsercloud.proto.node.v1.PublishRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PUBLISH = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_PUBLISH:
          serviceImpl.publish((io.browsercloud.proto.node.v1.PublishRequest) request,
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PublishResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getPublishMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.PublishRequest,
              io.browsercloud.proto.node.v1.PublishResponse>(
                service, METHODID_PUBLISH)))
        .build();
  }

  private static abstract class NodeEventServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NodeEventServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.browsercloud.proto.node.v1.NodeCommand.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NodeEventService");
    }
  }

  private static final class NodeEventServiceFileDescriptorSupplier
      extends NodeEventServiceBaseDescriptorSupplier {
    NodeEventServiceFileDescriptorSupplier() {}
  }

  private static final class NodeEventServiceMethodDescriptorSupplier
      extends NodeEventServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    NodeEventServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (NodeEventServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NodeEventServiceFileDescriptorSupplier())
              .addMethod(getPublishMethod())
              .build();
        }
      }
    }
    return result;
  }
}

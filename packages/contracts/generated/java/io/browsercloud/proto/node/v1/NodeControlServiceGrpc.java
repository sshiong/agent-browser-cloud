package io.browsercloud.proto.node.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
 * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: node/v1/node_command.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class NodeControlServiceGrpc {

  private NodeControlServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "browsercloud.node.v1.NodeControlService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PingRequest,
      io.browsercloud.proto.node.v1.PingResponse> getPingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Ping",
      requestType = io.browsercloud.proto.node.v1.PingRequest.class,
      responseType = io.browsercloud.proto.node.v1.PingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PingRequest,
      io.browsercloud.proto.node.v1.PingResponse> getPingMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PingRequest, io.browsercloud.proto.node.v1.PingResponse> getPingMethod;
    if ((getPingMethod = NodeControlServiceGrpc.getPingMethod) == null) {
      synchronized (NodeControlServiceGrpc.class) {
        if ((getPingMethod = NodeControlServiceGrpc.getPingMethod) == null) {
          NodeControlServiceGrpc.getPingMethod = getPingMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.PingRequest, io.browsercloud.proto.node.v1.PingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Ping"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeControlServiceMethodDescriptorSupplier("Ping"))
              .build();
        }
      }
    }
    return getPingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.DispatchRequest,
      io.browsercloud.proto.node.v1.DispatchResponse> getDispatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Dispatch",
      requestType = io.browsercloud.proto.node.v1.DispatchRequest.class,
      responseType = io.browsercloud.proto.node.v1.DispatchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.DispatchRequest,
      io.browsercloud.proto.node.v1.DispatchResponse> getDispatchMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.DispatchRequest, io.browsercloud.proto.node.v1.DispatchResponse> getDispatchMethod;
    if ((getDispatchMethod = NodeControlServiceGrpc.getDispatchMethod) == null) {
      synchronized (NodeControlServiceGrpc.class) {
        if ((getDispatchMethod = NodeControlServiceGrpc.getDispatchMethod) == null) {
          NodeControlServiceGrpc.getDispatchMethod = getDispatchMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.DispatchRequest, io.browsercloud.proto.node.v1.DispatchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Dispatch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.DispatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.DispatchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeControlServiceMethodDescriptorSupplier("Dispatch"))
              .build();
        }
      }
    }
    return getDispatchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.ProbeProxyBindingRequest,
      io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> getProbeProxyBindingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ProbeProxyBinding",
      requestType = io.browsercloud.proto.node.v1.ProbeProxyBindingRequest.class,
      responseType = io.browsercloud.proto.node.v1.ProbeProxyBindingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.ProbeProxyBindingRequest,
      io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> getProbeProxyBindingMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.ProbeProxyBindingRequest, io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> getProbeProxyBindingMethod;
    if ((getProbeProxyBindingMethod = NodeControlServiceGrpc.getProbeProxyBindingMethod) == null) {
      synchronized (NodeControlServiceGrpc.class) {
        if ((getProbeProxyBindingMethod = NodeControlServiceGrpc.getProbeProxyBindingMethod) == null) {
          NodeControlServiceGrpc.getProbeProxyBindingMethod = getProbeProxyBindingMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.ProbeProxyBindingRequest, io.browsercloud.proto.node.v1.ProbeProxyBindingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ProbeProxyBinding"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.ProbeProxyBindingRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.ProbeProxyBindingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeControlServiceMethodDescriptorSupplier("ProbeProxyBinding"))
              .build();
        }
      }
    }
    return getProbeProxyBindingMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.UploadProfileImportRequest,
      io.browsercloud.proto.node.v1.UploadProfileImportResponse> getUploadProfileImportMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UploadProfileImport",
      requestType = io.browsercloud.proto.node.v1.UploadProfileImportRequest.class,
      responseType = io.browsercloud.proto.node.v1.UploadProfileImportResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.UploadProfileImportRequest,
      io.browsercloud.proto.node.v1.UploadProfileImportResponse> getUploadProfileImportMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.UploadProfileImportRequest, io.browsercloud.proto.node.v1.UploadProfileImportResponse> getUploadProfileImportMethod;
    if ((getUploadProfileImportMethod = NodeControlServiceGrpc.getUploadProfileImportMethod) == null) {
      synchronized (NodeControlServiceGrpc.class) {
        if ((getUploadProfileImportMethod = NodeControlServiceGrpc.getUploadProfileImportMethod) == null) {
          NodeControlServiceGrpc.getUploadProfileImportMethod = getUploadProfileImportMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.UploadProfileImportRequest, io.browsercloud.proto.node.v1.UploadProfileImportResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UploadProfileImport"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.UploadProfileImportRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.UploadProfileImportResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeControlServiceMethodDescriptorSupplier("UploadProfileImport"))
              .build();
        }
      }
    }
    return getUploadProfileImportMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest,
      io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> getPresignEvidenceDownloadMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "PresignEvidenceDownload",
      requestType = io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest.class,
      responseType = io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest,
      io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> getPresignEvidenceDownloadMethod() {
    io.grpc.MethodDescriptor<io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest, io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> getPresignEvidenceDownloadMethod;
    if ((getPresignEvidenceDownloadMethod = NodeControlServiceGrpc.getPresignEvidenceDownloadMethod) == null) {
      synchronized (NodeControlServiceGrpc.class) {
        if ((getPresignEvidenceDownloadMethod = NodeControlServiceGrpc.getPresignEvidenceDownloadMethod) == null) {
          NodeControlServiceGrpc.getPresignEvidenceDownloadMethod = getPresignEvidenceDownloadMethod =
              io.grpc.MethodDescriptor.<io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest, io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "PresignEvidenceDownload"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeControlServiceMethodDescriptorSupplier("PresignEvidenceDownload"))
              .build();
        }
      }
    }
    return getPresignEvidenceDownloadMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NodeControlServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceStub>() {
        @java.lang.Override
        public NodeControlServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeControlServiceStub(channel, callOptions);
        }
      };
    return NodeControlServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NodeControlServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceBlockingStub>() {
        @java.lang.Override
        public NodeControlServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeControlServiceBlockingStub(channel, callOptions);
        }
      };
    return NodeControlServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NodeControlServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeControlServiceFutureStub>() {
        @java.lang.Override
        public NodeControlServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeControlServiceFutureStub(channel, callOptions);
        }
      };
    return NodeControlServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
   * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void ping(io.browsercloud.proto.node.v1.PingRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPingMethod(), responseObserver);
    }

    /**
     */
    default void dispatch(io.browsercloud.proto.node.v1.DispatchRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.DispatchResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDispatchMethod(), responseObserver);
    }

    /**
     * <pre>
     * mTLS-authenticated cold probe for an enabled Binding that has no active Session allocation.
     * credential_ref remains opaque: only the isolated Network Helper may resolve it to a locally
     * configured Provider route, and neither request nor response contains credential material.
     * </pre>
     */
    default void probeProxyBinding(io.browsercloud.proto.node.v1.ProbeProxyBindingRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getProbeProxyBindingMethod(), responseObserver);
    }

    /**
     * <pre>
     * mTLS-authenticated, bounded Profile archive ingress. The Control Plane
     * streams bytes without durable business persistence; the Node delegates validation and
     * checkpoint/object-store commit to the isolated Storage Helper.
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.UploadProfileImportRequest> uploadProfileImport(
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.UploadProfileImportResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getUploadProfileImportMethod(), responseObserver);
    }

    /**
     * <pre>
     * Purpose-bound evidence access. The Browser Node delegates signing to the isolated
     * Storage Helper and never returns Object Storage credentials.
     * </pre>
     */
    default void presignEvidenceDownload(io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPresignEvidenceDownloadMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service NodeControlService.
   * <pre>
   * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
   * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
   * </pre>
   */
  public static abstract class NodeControlServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return NodeControlServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service NodeControlService.
   * <pre>
   * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
   * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
   * </pre>
   */
  public static final class NodeControlServiceStub
      extends io.grpc.stub.AbstractAsyncStub<NodeControlServiceStub> {
    private NodeControlServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeControlServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeControlServiceStub(channel, callOptions);
    }

    /**
     */
    public void ping(io.browsercloud.proto.node.v1.PingRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void dispatch(io.browsercloud.proto.node.v1.DispatchRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.DispatchResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDispatchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * mTLS-authenticated cold probe for an enabled Binding that has no active Session allocation.
     * credential_ref remains opaque: only the isolated Network Helper may resolve it to a locally
     * configured Provider route, and neither request nor response contains credential material.
     * </pre>
     */
    public void probeProxyBinding(io.browsercloud.proto.node.v1.ProbeProxyBindingRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getProbeProxyBindingMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * mTLS-authenticated, bounded Profile archive ingress. The Control Plane
     * streams bytes without durable business persistence; the Node delegates validation and
     * checkpoint/object-store commit to the isolated Storage Helper.
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.UploadProfileImportRequest> uploadProfileImport(
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.UploadProfileImportResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getUploadProfileImportMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Purpose-bound evidence access. The Browser Node delegates signing to the isolated
     * Storage Helper and never returns Object Storage credentials.
     * </pre>
     */
    public void presignEvidenceDownload(io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest request,
        io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPresignEvidenceDownloadMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service NodeControlService.
   * <pre>
   * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
   * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
   * </pre>
   */
  public static final class NodeControlServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<NodeControlServiceBlockingStub> {
    private NodeControlServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeControlServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeControlServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public io.browsercloud.proto.node.v1.PingResponse ping(io.browsercloud.proto.node.v1.PingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPingMethod(), getCallOptions(), request);
    }

    /**
     */
    public io.browsercloud.proto.node.v1.DispatchResponse dispatch(io.browsercloud.proto.node.v1.DispatchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDispatchMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * mTLS-authenticated cold probe for an enabled Binding that has no active Session allocation.
     * credential_ref remains opaque: only the isolated Network Helper may resolve it to a locally
     * configured Provider route, and neither request nor response contains credential material.
     * </pre>
     */
    public io.browsercloud.proto.node.v1.ProbeProxyBindingResponse probeProxyBinding(io.browsercloud.proto.node.v1.ProbeProxyBindingRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getProbeProxyBindingMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Purpose-bound evidence access. The Browser Node delegates signing to the isolated
     * Storage Helper and never returns Object Storage credentials.
     * </pre>
     */
    public io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse presignEvidenceDownload(io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPresignEvidenceDownloadMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service NodeControlService.
   * <pre>
   * Browser Node 的最小控制面 RPC。Session API 通过 Outbox 异步投递命令；
   * Runtime 状态以 NodeEventService 成功提交的版本化事件为准。
   * </pre>
   */
  public static final class NodeControlServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<NodeControlServiceFutureStub> {
    private NodeControlServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeControlServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeControlServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.browsercloud.proto.node.v1.PingResponse> ping(
        io.browsercloud.proto.node.v1.PingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPingMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<io.browsercloud.proto.node.v1.DispatchResponse> dispatch(
        io.browsercloud.proto.node.v1.DispatchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDispatchMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * mTLS-authenticated cold probe for an enabled Binding that has no active Session allocation.
     * credential_ref remains opaque: only the isolated Network Helper may resolve it to a locally
     * configured Provider route, and neither request nor response contains credential material.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.browsercloud.proto.node.v1.ProbeProxyBindingResponse> probeProxyBinding(
        io.browsercloud.proto.node.v1.ProbeProxyBindingRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getProbeProxyBindingMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Purpose-bound evidence access. The Browser Node delegates signing to the isolated
     * Storage Helper and never returns Object Storage credentials.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse> presignEvidenceDownload(
        io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPresignEvidenceDownloadMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_PING = 0;
  private static final int METHODID_DISPATCH = 1;
  private static final int METHODID_PROBE_PROXY_BINDING = 2;
  private static final int METHODID_PRESIGN_EVIDENCE_DOWNLOAD = 3;
  private static final int METHODID_UPLOAD_PROFILE_IMPORT = 4;

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
        case METHODID_PING:
          serviceImpl.ping((io.browsercloud.proto.node.v1.PingRequest) request,
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PingResponse>) responseObserver);
          break;
        case METHODID_DISPATCH:
          serviceImpl.dispatch((io.browsercloud.proto.node.v1.DispatchRequest) request,
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.DispatchResponse>) responseObserver);
          break;
        case METHODID_PROBE_PROXY_BINDING:
          serviceImpl.probeProxyBinding((io.browsercloud.proto.node.v1.ProbeProxyBindingRequest) request,
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.ProbeProxyBindingResponse>) responseObserver);
          break;
        case METHODID_PRESIGN_EVIDENCE_DOWNLOAD:
          serviceImpl.presignEvidenceDownload((io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest) request,
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse>) responseObserver);
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
        case METHODID_UPLOAD_PROFILE_IMPORT:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.uploadProfileImport(
              (io.grpc.stub.StreamObserver<io.browsercloud.proto.node.v1.UploadProfileImportResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getPingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.PingRequest,
              io.browsercloud.proto.node.v1.PingResponse>(
                service, METHODID_PING)))
        .addMethod(
          getDispatchMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.DispatchRequest,
              io.browsercloud.proto.node.v1.DispatchResponse>(
                service, METHODID_DISPATCH)))
        .addMethod(
          getProbeProxyBindingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.ProbeProxyBindingRequest,
              io.browsercloud.proto.node.v1.ProbeProxyBindingResponse>(
                service, METHODID_PROBE_PROXY_BINDING)))
        .addMethod(
          getUploadProfileImportMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.UploadProfileImportRequest,
              io.browsercloud.proto.node.v1.UploadProfileImportResponse>(
                service, METHODID_UPLOAD_PROFILE_IMPORT)))
        .addMethod(
          getPresignEvidenceDownloadMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.browsercloud.proto.node.v1.PresignEvidenceDownloadRequest,
              io.browsercloud.proto.node.v1.PresignEvidenceDownloadResponse>(
                service, METHODID_PRESIGN_EVIDENCE_DOWNLOAD)))
        .build();
  }

  private static abstract class NodeControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NodeControlServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.browsercloud.proto.node.v1.NodeCommand.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NodeControlService");
    }
  }

  private static final class NodeControlServiceFileDescriptorSupplier
      extends NodeControlServiceBaseDescriptorSupplier {
    NodeControlServiceFileDescriptorSupplier() {}
  }

  private static final class NodeControlServiceMethodDescriptorSupplier
      extends NodeControlServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    NodeControlServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (NodeControlServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NodeControlServiceFileDescriptorSupplier())
              .addMethod(getPingMethod())
              .addMethod(getDispatchMethod())
              .addMethod(getProbeProxyBindingMethod())
              .addMethod(getUploadProfileImportMethod())
              .addMethod(getPresignEvidenceDownloadMethod())
              .build();
        }
      }
    }
    return result;
  }
}

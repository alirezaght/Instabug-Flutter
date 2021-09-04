import 'dart:io';

import 'package:instabug_flutter/NetworkLogger.dart';
import 'package:instabug_flutter/models/network_data.dart';

class HttpClientLogger {
  final requests = <int, NetworkData>{};

  NetworkData? _getRequestData(int requestHashCode) {
    if (requests[requestHashCode] != null) {
      return requests.remove(requestHashCode);
    }
    return null;
  }

  void onRequest(HttpClientRequest request, {dynamic requestBody}) {
    final requestHeaders = <String, dynamic>{};
    request.headers.forEach((String header, dynamic value) {
      requestHeaders[header] = value[0];
    });
    final NetworkData requestData = NetworkData(
      startTime: DateTime.now(),
      method: request.method,
      url: request.uri.toString(),
      requestHeaders: requestHeaders,
      requestBody: requestBody ?? '',
      requestBodySize: int.parse(requestHeaders['content-length'] ?? '0'),
    );

    requests[request.hashCode] = requestData;
  }

  void onResponse(HttpClientResponse response, HttpClientRequest request,
      {dynamic responseBody}) {
    final DateTime endTime = DateTime.now();
    final networkData = _getRequestData(request.hashCode);
    final responseHeaders = <String, dynamic>{};

    if (networkData == null) {
      return;
    }

    request.headers.forEach((String header, dynamic value) {
      responseHeaders[header] = value[0];
    });

    NetworkLogger.networkLog(networkData.copyWith(
      status: response.statusCode,
      duration: endTime.difference(networkData.startTime).inMicroseconds,
      contentType: response.headers.contentType?.value,
      responseHeaders: responseHeaders,
      responseBody: responseBody,
      errorCode: 0,
      errorDomain: response.statusCode != 0 ? '' : 'ClientError',
      responseBodySize: int.parse(responseHeaders['content-length'] ?? '0'),
    ));
  }
}

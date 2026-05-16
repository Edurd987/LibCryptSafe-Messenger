#pragma once
#include <vector>
#include <cstdint>
#include <string>
#include <functional>

namespace Transport {

// Статус соединения
enum class Status {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    TRANSPORT_ERROR
};

// Структура сообщения между узлами
struct Message {
    std::vector<uint8_t> payload;   // Уже зашифрованные данные
    std::string          sender_id; // Идентификатор отправителя
    uint64_t             timestamp; // Unix time
};

// ── Абстрактный интерфейс — все транспорты обязаны его реализовать ──
class ITransport {
public:
    virtual ~ITransport() = default;

    // Установить соединение с удалённым узлом
    virtual bool connect(const std::string& address, uint16_t port) = 0;

    // Разорвать соединение
    virtual void disconnect() = 0;

    // Отправить зашифрованное сообщение
    virtual bool send(const Message& msg) = 0;

    // Зарегистрировать callback на входящее сообщение
    virtual void on_receive(std::function<void(const Message&)> callback) = 0;

    // Текущий статус канала
    virtual Status status() const = 0;

    // Человекочитаемое имя транспорта (для логов)
    virtual std::string name() const = 0;
};

} // namespace Transport
